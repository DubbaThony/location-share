package protocol

import (
	"errors"
	"net"

	"github.com/DubbaThony/share-server/ifaces"
	"github.com/DubbaThony/share-server/protocol/frametypes"
	"github.com/DubbaThony/share-server/protocol/marshaller"
	"github.com/DubbaThony/share-server/state"
	"github.com/DubbaThony/share-server/util"
)

func (h *handler) handle() {
	buff := make([]byte, udpLimit, udpLimit)
	for {
		n, remote, err := h.sock.ReadFromUDP(buff)
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				h.log.Debug().Msg("Handler: exiting - connection closed")
				h.stop.Send(struct{}{})
				return
			}
			h.log.Error().Err(err).Msg("failed to read frame")
			continue
		}
		data := make([]byte, n)
		copy(data, buff[:n])
		go h.handleBlob(data, remote)
	}
}

func (h *handler) transmitFrame(body []byte, connId uint64, frameType frametypes.FrameType, remote *net.UDPAddr) error {
	return h.transmitFrameRaw(&frametypes.AbstractFrame{
		ConnID:    connId,
		FrameType: frameType,
		Body:      body,
	}, remote)
}

func (h *handler) transmitFrameRaw(f *frametypes.AbstractFrame, remote *net.UDPAddr) error {
	_, err := h.sock.WriteToUDP(marshaller.New().MarshalAbstractFrame(f), remote)
	if err != nil {
		h.log.Error().Err(err).Msg("failed to write frame to socket")
		return err
	}
	return nil
}

func (h *handler) killConnectionFatal(connId uint64) {
	var err error
	if !state.ConnMap.Update(connId, func(conn *ifaces.Connection) {
		err = h.transmitFrame(marshaller.New().MarshalCtrl(&frametypes.CtrlFrame{
			CtrlType:  frametypes.CtrlTypeFatalError,
			CtrlNonce: util.CSPRNGInt[uint16](), // NACKing frame, insufficient fucks given
			Extra:     nil,
		}, conn.TxNonce, conn.TxCrypt), conn.ConnectionID, frametypes.FrameTyCtrl, conn.LastKnownAddr)
	}) {
		h.log.Error().Err(err).Msg("failed to kill connection: connection already dead")
		return
	}
	if err != nil {
		h.log.Error().Err(err).Msg("failed to kill connection: failed to write connection is gone")
	}
}
