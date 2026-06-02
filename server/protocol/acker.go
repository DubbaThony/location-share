package protocol

import (
	"encoding/binary"
	"sync"
	"time"

	"github.com/DubbaThony/share-server/ifaces"
	"github.com/DubbaThony/share-server/protocol/frametypes"
	"github.com/DubbaThony/share-server/protocol/marshaller"
	"github.com/DubbaThony/share-server/state"
	"github.com/DubbaThony/share-server/util"
)

const ctrlTyBurstSize = 3

type ackRq struct {
	connId  uint64
	reTxCnt uint8
	raw     []byte // retransmitting same data with same nonce is OK as long as data is EXACTLY same.
	tl      time.Time
}

type acker struct {
	mu       *sync.Mutex
	ackMap   map[uint64]*ackRq
	rxNonces map[uint64]time.Time
	rxClr    *util.THelper[uint64]
	txClr    *util.THelper[uint64]
}

func newAcker() *acker {
	return &acker{
		mu:       &sync.Mutex{},
		ackMap:   make(map[uint64]*ackRq),
		rxNonces: make(map[uint64]time.Time),
		rxClr:    util.NewTHelper[uint64](),
		txClr:    util.NewTHelper[uint64](),
	}
}

func (h *handler) transmitAckingFrame(t frametypes.CtrlType, extra []byte, connId uint64) {
	if extra == nil {
		extra = make([]byte, 0, 0)
	}
	f := &frametypes.CtrlFrame{
		CtrlType:  t,
		CtrlNonce: 0,
		Extra:     extra,
	}
	h.acker.mu.Lock()
	defer h.acker.mu.Unlock()
	var n uint64
	for {
		f.CtrlNonce = util.CSPRNGInt[uint16]()
		n = connIdxAckId(connId, f.CtrlNonce)
		if _, ok := h.acker.ackMap[n]; !ok {
			break
		}
	}
	var pld []byte
	var err error
	if !state.ConnMap.Update(connId, func(conn *ifaces.Connection) {
		pld = marshaller.New().MarshalAbstractFrame(&frametypes.AbstractFrame{
			ConnID:    conn.ConnectionID,
			FrameType: frametypes.FrameTyCtrl,
			Body:      marshaller.New().MarshalCtrl(f, conn.TxNonce, conn.TxCrypt),
		})
		for i := 0; i < ctrlTyBurstSize; i++ {
			_, err = h.sock.WriteToUDP(pld, conn.LastKnownAddr) // yes, we save only last err. That's fine.
		}
	}) {
		h.log.Error().Msgf("cannot send acking frame for connId %d while connection is dead", connId)
		return
	}
	if err != nil {
		h.log.Error().Err(err).Msg("fail to send acking frame!")
		return // don't save acking frame to map. //todo: this may cause breakage since caller will believe it's done. Most likely port closed due to server shutting down, anyway.
	}
	ar := &ackRq{
		connId:  connId,
		reTxCnt: 0,
		raw:     pld,
		tl:      time.Now().Add(ifaces.TimeoutConnected),
	}
	h.acker.ackMap[n] = ar
	h.acker.txClr.Push(ar.nextTime(), n)
}

func (h *handler) registerAck(conId uint64, ackId uint16) {
	k := connIdxAckId(conId, ackId)
	h.acker.mu.Lock()
	defer h.acker.mu.Unlock()
	a, ok := h.acker.ackMap[k]
	if !ok {
		h.log.Debug().Msg("registerAck: acking frame not found (OK)") // not warn etc. normal condition
		return
	}
	h.log.Debug().Msgf("frame ACKed OK after %d retransmissions", a.reTxCnt)
	delete(h.acker.ackMap, k)
}

func (h *handler) remoteCtrlIsDupe(conId uint64, ackId uint16) bool {
	k := connIdxAckId(conId, ackId)
	h.acker.mu.Lock()
	defer h.acker.mu.Unlock()
	_, ok := h.acker.rxNonces[k]
	if !ok {
		h.acker.rxNonces[k] = time.Now()
		h.acker.rxClr.Push(ifaces.TimeoutConnected, k)
	}
	return ok
}

func (h *handler) handleAckClrs() {
	go h.handleAckRxClrs()
	go h.handleAckTxClrs()
}
func (h *handler) handleAckTxClrs() {
	a := h.acker
	stop := h.stop.Consumer(nil)
	for {
		select {
		case ackKey := <-a.txClr.C:
			a.mu.Lock()
			rq, ok := a.ackMap[ackKey]
			if ok {
				// found in map, retx needed or dead conn
				if rq.timedOut() {
					h.log.Warn().Msgf("ACKing frame time out for connection %d. Killing connection", rq.connId)
					// pointless to transmit bye / error frames since conn is dead.
					state.ConnMap.Delete(rq.connId)
					go h.pub.Teardown(rq.connId)
					delete(h.acker.ackMap, ackKey)
					a.mu.Unlock()
					continue
				}
				// not timed out, retx
				if !state.ConnMap.Update(rq.connId, func(conn *ifaces.Connection) {
					_, err := h.sock.WriteToUDP(rq.raw, conn.LastKnownAddr)
					if err != nil {
						h.log.Err(err).Msg("fail to retransmit frame")
					} else {
						rq.reTxCnt++
						h.acker.txClr.Push(rq.nextTime(), ackKey)
					}
				}) {
					// oooor apparently dead conn ¯\_(ツ)_/¯
					delete(h.acker.ackMap, ackKey)
				}
			}
			a.mu.Unlock()
		case <-stop:
			a.txClr.Close()
			return
		}
	}
}

func (h *handler) handleAckRxClrs() {
	a := h.acker
	stop := h.stop.Consumer(nil)
	for {
		select {
		case ackId := <-a.rxClr.C:
			a.mu.Lock()
			delete(a.rxNonces, ackId)
			a.mu.Unlock()
		case <-stop:
			a.rxClr.Close()
			return
		}
	}
}

// trivial mixer, u64 is big enough to avoid collisions. We aren't gifted with u80 support and u48 for connid is impractical.
func connIdxAckId(connId uint64, ackId uint16) uint64 {
	ab := make([]byte, 2)
	cb := make([]byte, 8)
	binary.LittleEndian.PutUint16(ab, ackId)
	binary.LittleEndian.PutUint64(cb, connId)
	for i := range cb {
		cb[i] = cb[i] ^ ab[i%2]
	}
	return binary.LittleEndian.Uint64(cb)
}

// 500m, 1000, 1500, 2000, 2500 ...
func (a *ackRq) nextTime() time.Duration {
	return time.Duration(a.reTxCnt+1) * time.Millisecond * 500
}
func (a *ackRq) timedOut() bool {
	return time.Now().After(a.tl)
}
