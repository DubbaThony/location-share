package ifaces

import (
	"crypto/cipher"
	"net"
	"time"

	"github.com/DubbaThony/share-server/util"
)

const TimeoutHandshake = 30 * time.Second
const TimeoutConnected = 600 * time.Second

type ConnPhase uint8

const (
	// ConnPhaseUndefined is empty / default value. Code relies on this specifically.
	ConnPhaseUndefined ConnPhase = iota
	ConnPhaseHandshake
	ConnPhaseConnected
	ConnPhaseRequireCleanup
)

type Connection struct {
	ConnectionID  uint64 // ConnID after construction MUST NEVER BE CHANGED
	ConnPhase     ConnPhase
	TxNonce       *uint64
	TxCrypt       cipher.AEAD // AEADs can be pulled from connection AND MUST NEVER BE CHANGED
	RxCrypt       cipher.AEAD
	LastKnownAddr *net.UDPAddr
	LastSeen      time.Time
	Features      *util.Bitmask[uint32]
	PublicAddr    string
}
