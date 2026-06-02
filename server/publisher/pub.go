package publisher

import (
	"encoding/hex"
	"encoding/json"
	"net/http"
	"sync"

	"github.com/DubbaThony/share-server/ifaces"
	"github.com/DubbaThony/share-server/util"
	"github.com/gorilla/websocket"
	"github.com/labstack/echo/v4"
	"github.com/rs/zerolog"
)

// upgrader turns the incoming HTTP request into a websocket connection.
// CheckOrigin is permissive here; tighten it before exposing publicly.
var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool { return true },
}

type channel struct {
	identifier uint64
	key        string
	channels   *util.ChanDemux[[]byte]
}

type publisher struct {
	l      zerolog.Logger
	chMu   *sync.RWMutex
	chMap  *util.MuMap[uint64, channel]
	key2id map[string]uint64
}

func New(l zerolog.Logger) (ifaces.Publisher, echo.HandlerFunc) {
	p := &publisher{
		l:      l,
		chMu:   &sync.RWMutex{},
		chMap:  util.NewMuMap[uint64, channel](),
		key2id: make(map[string]uint64),
	}
	return p, p.echoHandler()
}

func (p *publisher) Create(u uint64, s string) error {
	p.l.Debug().Msgf("Create(%d, %s)", u, s)
	p.chMu.Lock()
	defer p.chMu.Unlock()
	p.key2id[s] = u
	p.chMap.Set(u, &channel{
		identifier: u,
		key:        s,
		channels:   util.NewChanDemux[[]byte](p.l),
	})

	return nil
}

func (p *publisher) Push(u uint64, bytes []byte) error {
	p.l.Trace().Msgf("Push(%d, %s)", u, hex.EncodeToString(bytes))
	p.chMap.Read(u, func(v *channel) {
		v.channels.Send(bytes)
	})
	return nil
}

func (p *publisher) CountSubs(u uint64) (uint64, error) {
	count := uint64(0)
	p.chMap.Read(u, func(v *channel) {
		count = v.channels.Consumers()
	})
	p.l.Debug().Msgf("CountSubs(%d) -> %d", u, count)
	return count, nil
}

func (p *publisher) Teardown(u uint64) {
	p.l.Trace().Msgf("Teardown(%d)", u)
	p.chMap.Read(u, func(v *channel) {
		p.chMu.Lock()
		defer p.chMu.Unlock()
		delete(p.key2id, v.key)
		v.channels.Close()
	})
	p.chMap.Delete(u)
}

func (p *publisher) Exists(key string) bool {
	p.chMu.Lock()
	defer p.chMu.Unlock()
	_, ok := p.key2id[key]
	return ok
}

func (p *publisher) echoHandler() echo.HandlerFunc {
	return func(c echo.Context) error {
		p.l.Debug().Msgf("echoHandler() entrypoint")
		key := c.Param("key")
		p.l.Debug().Msgf("key=%s", key)
		p.chMu.RLock()
		identifier, ok := p.key2id[key]
		p.chMu.RUnlock()
		if !ok {
			p.l.Debug().Msgf("key=%s was not found, returning 404", key)
			return c.JSON(http.StatusNotFound, &ifaces.BasicResponse[struct{}]{
				Status: false,
				Error:  util.P("invalid key"),
			})
		}
		var inputs <-chan []byte
		var cf func(<-chan []byte)
		ok = p.chMap.Read(identifier, func(v *channel) {
			inputs = v.channels.Consumer(util.P(50))
			cf = v.channels.CloseChan
		})
		p.l.Debug().Msgf("acquire input chan ok=%t", ok)
		if !ok {
			return c.JSON(http.StatusInternalServerError, &ifaces.BasicResponse[struct{}]{
				Status: false,
				Error:  util.P("failed to resolve identifier"),
			})
		}
		defer cf(inputs)

		conn, err := upgrader.Upgrade(c.Response(), c.Request(), nil)
		p.l.Debug().Err(err).Msgf("upgrader returned")
		if err != nil {
			// Upgrade already wrote an error response on failure.
			return nil
		}
		defer func() { _ = conn.Close() }()

		openMu := &sync.Mutex{}
		open := true
		wg := sync.WaitGroup{}
		wg.Add(1)

		go func() {
			p.l.Debug().Uint64("connId", identifier).Msgf("handling writer")
			defer p.l.Debug().Uint64("connId", identifier).Msgf("closing writer")
			for msg := range inputs {
				err := conn.WriteMessage(websocket.BinaryMessage, msg)
				if err != nil {
					p.l.Warn().Err(err).Msgf("Failed to write to websocket")
				}
			}
			openMu.Lock()
			wasOpen := open
			open = false
			openMu.Unlock()
			wg.Done()

			if wasOpen {
				pld, err := json.Marshal(&ifaces.WsMsg[struct{}]{
					MsgType: ifaces.WsMsgTypePublisherGone,
				})
				if err != nil {
					p.l.Warn().Err(err).Msgf("Failed to serialize payload")
					go conn.Close()
					return
				}
				err = conn.WriteMessage(websocket.TextMessage, pld)
				if err != nil {
					p.l.Warn().Err(err).Msgf("Failed to write to websocket")
					go conn.Close()
					return
				}
			}
		}()

		p.l.Debug().Uint64("connId", identifier).Msgf("handling reader")
		defer p.l.Debug().Uint64("connId", identifier).Msgf("closing reader")
		for {
			msgType, data, err := conn.ReadMessage()
			if err != nil {
				openMu.Lock()
				open = false
				openMu.Unlock()
				if websocket.IsUnexpectedCloseError(err,
					websocket.CloseNormalClosure, websocket.CloseGoingAway) {
					p.l.Warn().Err(err).Msg("websocket closed unexpectedly")
				}
				return nil
			}

			p.l.Debug().Msgf("ws recv (type=%d): %s", msgType, hex.EncodeToString(data))
			// todo: does the client have any capability to talk to us? I dont think taht's the case... Silently discarding msgs works.
		}
	}
}
