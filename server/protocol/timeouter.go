package protocol

import (
	"time"

	"github.com/DubbaThony/share-server/ifaces"
	"github.com/DubbaThony/share-server/state"
)

const timeoutResolution = 250 * time.Millisecond

func (h *handler) timeoutWatchSession(session uint64) {

}

func (h *handler) timeouter() {
	tkr := time.NewTicker(timeoutResolution)
	stop := h.stop.Consumer(nil)
	for {
		select {
		case <-tkr.C:
			state.ConnMap.Foreach(func(sesionId uint64, conn *ifaces.Connection) {
				to := time.Duration(0)
				switch conn.ConnPhase {
				case ifaces.ConnPhaseHandshake:
					to = ifaces.TimeoutHandshake
				case ifaces.ConnPhaseConnected:
					to = ifaces.TimeoutConnected
				case ifaces.ConnPhaseRequireCleanup:
					// hack xD
					to = -time.Hour
				default:
					panic("corrupt internal state")
				}
				if time.Now().After(conn.LastSeen.Add(to)) {
					h.log.Warn().Msgf("RIP: session %d timed out", sesionId)
					go state.ConnMap.Delete(sesionId)
					go h.pub.Teardown(sesionId)
				}
			})
		case <-stop:
			return
		}
	}
}
