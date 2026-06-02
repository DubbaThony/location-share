package ifaces

import "crypto/ecdh"

type Identity interface {
	ProtoSignature(ecdhClient, ecdhServer *ecdh.PublicKey, remoteConnId, serverConnId uint64) [64]byte
	Signature(pld []byte) []byte
	Identity() []byte
}
