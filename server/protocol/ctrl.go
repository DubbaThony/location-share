package protocol

import (
	"encoding/binary"
	"math"

	"github.com/DubbaThony/share-server/protocol/frametypes"
	"github.com/DubbaThony/share-server/state"
)

func (h *handler) handleCtrlTypeGetSubCount(ctrl *frametypes.CtrlFrame, f *frametypes.AbstractFrame) {
	subcnt, err := h.pub.CountSubs(f.ConnID)
	if err != nil {
		subcnt = math.MaxUint64
		h.log.Error().Err(err).Msgf("Error getting sub count, sending f-fill response.")
	}
	buff := make([]byte, 8)
	binary.LittleEndian.PutUint64(buff, subcnt)
	go h.transmitAckingFrame(frametypes.CtrlTypeGetSubCount, buff, f.ConnID)
}

func (h *handler) handleCtrlTypeFatalMsgError(ctrl *frametypes.CtrlFrame, f *frametypes.AbstractFrame) {
	h.log.Error().Str("msg", string(ctrl.Extra)).Msgf("Received Fatal Msg Error. Killing connection.")
	go h.pub.Teardown(f.ConnID)
	go state.ConnMap.Delete(f.ConnID)
}

func (h *handler) handleCtrlTypeFatalError(ctrl *frametypes.CtrlFrame, f *frametypes.AbstractFrame) {
	h.log.Error().Msgf("Received Fatal Error. Killing connection.")
	go h.pub.Teardown(f.ConnID)
	go state.ConnMap.Delete(f.ConnID)
}

func (h *handler) handleCtrlTypeBye(ctrl *frametypes.CtrlFrame, f *frametypes.AbstractFrame) {
	h.log.Info().Msg("Client gracefully disconnected.")
	go h.pub.Teardown(f.ConnID)
	go state.ConnMap.Delete(f.ConnID)
}
