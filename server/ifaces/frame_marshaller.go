package ifaces

import (
	"crypto/cipher"

	"github.com/DubbaThony/share-server/protocol/frametypes"
)

type FrameMarshaler interface {
	MarshalAbstractFrame(frame *frametypes.AbstractFrame) []byte
	UnmarshalAbstractFrame(data []byte) (*frametypes.AbstractFrame, error)

	MarshalHandshakeClient(frame *frametypes.ClientHandshakeFrame) []byte
	UnmarshalHandshakeClient(data []byte) (*frametypes.ClientHandshakeFrame, error)
	MarshalHandshakeServer(frame *frametypes.ServerHandshakeFrame) []byte
	UnmarshalHandshakeServer(data []byte) (*frametypes.ServerHandshakeFrame, error)
	MarshalEncryptedHandshakeClient(frame *frametypes.EncryptedClientHandshakeFrame, nonce *uint64, crypt cipher.AEAD) []byte
	UnmarshalEncryptedHandshakeClient(data []byte, crypt cipher.AEAD) (*frametypes.EncryptedClientHandshakeFrame, error)
	MarshalEncryptedHandshakeServer(frame *frametypes.EncryptedServerHandshakeFrame, nonce *uint64, crypt cipher.AEAD) []byte
	UnmarshalEncryptedHandshakeServer(data []byte, crypt cipher.AEAD) (*frametypes.EncryptedServerHandshakeFrame, error)
	MarshalPing(frame *frametypes.PingFrame, nonce *uint64, crypt cipher.AEAD) []byte
	UnmarshalPing(data []byte, crypt cipher.AEAD) (*frametypes.PingFrame, error)
	MarshalData(frame *frametypes.DataFrame, nonce *uint64, crypt cipher.AEAD) []byte
	UnmarshalData(data []byte, crypt cipher.AEAD) (*frametypes.DataFrame, error)
	MarshalCtrl(frame *frametypes.CtrlFrame, nonce *uint64, crypt cipher.AEAD) []byte
	UnmarshalCtrl(data []byte, crypt cipher.AEAD) (*frametypes.CtrlFrame, error)

	ValidateFrameType(fr *frametypes.AbstractFrame) bool
}
