package marshaller

import (
	"crypto/cipher"
	"encoding/binary"
	"hash/crc32"

	"github.com/DubbaThony/share-server/ifaces"
	"github.com/DubbaThony/share-server/protocol/frametypes"
)

type frameMarshaler struct{}

func New() ifaces.FrameMarshaler {
	return &frameMarshaler{}
}

func (f *frameMarshaler) MarshalAbstractFrame(frame *frametypes.AbstractFrame) []byte {
	buf := (&statefulEncoder{}).
		U64(frame.ConnID).
		U8(uint8(frame.FrameType)).
		BytesNoHeader(frame.Body).
		U32(0).
		buffer
	crc := crc32.ChecksumIEEE(buf)
	binary.LittleEndian.PutUint32(buf[len(buf)-4:], crc)
	return buf
}

func (f *frameMarshaler) UnmarshalAbstractFrame(data []byte) (*frametypes.AbstractFrame, error) {
	if len(data) < 13 {
		return nil, ifaces.ErrPacketMalformed
	}
	n := len(data)
	storedCRC := binary.LittleEndian.Uint32(data[n-4:])

	h := crc32.NewIEEE()
	_, _ = h.Write(data[:n-4])
	_, _ = h.Write([]byte{0, 0, 0, 0})
	if h.Sum32() != storedCRC {
		return nil, ifaces.ErrPacketMalformed
	}

	d := &statefulDecoder{input: data[:n-4]}
	o := &frametypes.AbstractFrame{
		ConnID:    d.U64(),
		FrameType: frametypes.FrameType(d.U8()),
		Body:      d.BytesNoHeader(uint16(n - 4 - 9)),
		CRC32:     storedCRC,
	}
	if d.Ok() {
		return o, nil
	}
	return nil, ifaces.ErrPacketMalformed
}

func (f *frameMarshaler) MarshalHandshakeClient(frame *frametypes.ClientHandshakeFrame) []byte {
	return (&statefulEncoder{}).
		BytesNoHeader(frame.ECDH[:]).
		U8(frame.ProtoVersion).
		buffer
}

func (f *frameMarshaler) UnmarshalHandshakeClient(data []byte) (*frametypes.ClientHandshakeFrame, error) {
	d := &statefulDecoder{input: data}
	o := &frametypes.ClientHandshakeFrame{
		ECDH:         [65]byte(d.BytesNoHeader(65)),
		ProtoVersion: d.U8(),
	}
	if d.Ok() {
		return o, nil
	}
	return nil, ifaces.ErrPacketMalformed
}

func (f *frameMarshaler) MarshalHandshakeServer(frame *frametypes.ServerHandshakeFrame) []byte {
	return (&statefulEncoder{}).
		BytesNoHeader(frame.ECDH[:]).
		BytesNoHeader(frame.Signature[:]).
		U8(uint8(frame.Status)).
		U64(frame.ConnectionID).
		buffer
}

func (f *frameMarshaler) UnmarshalHandshakeServer(data []byte) (*frametypes.ServerHandshakeFrame, error) {
	d := &statefulDecoder{input: data}
	o := &frametypes.ServerHandshakeFrame{
		ECDH:         [65]byte(d.BytesNoHeader(65)),
		Signature:    [64]byte(d.BytesNoHeader(64)),
		Status:       frametypes.HandshakeStatusByte(d.U8()),
		ConnectionID: d.U64(),
	}
	if d.Ok() {
		return o, nil
	}
	return nil, ifaces.ErrPacketMalformed
}

func (f *frameMarshaler) MarshalEncryptedHandshakeClient(frame *frametypes.EncryptedClientHandshakeFrame, nonce *uint64, crypt cipher.AEAD) []byte {
	return f.crypt(
		(&statefulEncoder{}).
			U32(uint32(frame.FeatFlag)).
			buffer,
		crypt,
		frametypes.FrameTyEncryptedHandshakeClient,
		nonce,
	)
}

func (f *frameMarshaler) UnmarshalEncryptedHandshakeClient(data []byte, crypt cipher.AEAD) (*frametypes.EncryptedClientHandshakeFrame, error) {
	plain, err := f.deCrypt(data, crypt)
	if err != nil {
		return nil, err
	}
	d := &statefulDecoder{input: plain}
	o := &frametypes.EncryptedClientHandshakeFrame{
		FeatFlag: frametypes.FeatureFlags(d.U32()),
	}
	if d.Ok() {
		return o, nil
	}
	return nil, ifaces.ErrPacketMalformed
}

func (f *frameMarshaler) MarshalEncryptedHandshakeServer(frame *frametypes.EncryptedServerHandshakeFrame, nonce *uint64, crypt cipher.AEAD) []byte {
	return f.crypt(
		(&statefulEncoder{}).
			U32(uint32(frame.FeatFlag)).
			BytesNoHeader(frame.PublicAlias[:]).
			buffer,
		crypt,
		frametypes.FrameTyEncryptedHandshakeServer,
		nonce,
	)
}

func (f *frameMarshaler) UnmarshalEncryptedHandshakeServer(data []byte, crypt cipher.AEAD) (*frametypes.EncryptedServerHandshakeFrame, error) {
	plain, err := f.deCrypt(data, crypt)
	if err != nil {
		return nil, err
	}
	d := &statefulDecoder{input: plain}
	o := &frametypes.EncryptedServerHandshakeFrame{
		FeatFlag:    frametypes.FeatureFlags(d.U32()),
		PublicAlias: [32]byte(d.BytesNoHeader(32)),
	}
	if d.Ok() {
		return o, nil
	}
	return nil, ifaces.ErrPacketMalformed
}

func (f *frameMarshaler) MarshalPing(frame *frametypes.PingFrame, nonce *uint64, crypt cipher.AEAD) []byte {
	return f.crypt(
		(&statefulEncoder{}).
			U16(frame.Nonce).
			buffer,
		crypt,
		frametypes.FrameTyPing,
		nonce,
	)
}

func (f *frameMarshaler) UnmarshalPing(data []byte, crypt cipher.AEAD) (*frametypes.PingFrame, error) {
	plain, err := f.deCrypt(data, crypt)
	if err != nil {
		return nil, err
	}
	d := &statefulDecoder{input: plain}
	o := &frametypes.PingFrame{
		Nonce: d.U16(),
	}
	if d.Ok() {
		return o, nil
	}
	return nil, ifaces.ErrPacketMalformed
}

func (f *frameMarshaler) MarshalData(frame *frametypes.DataFrame, nonce *uint64, crypt cipher.AEAD) []byte {
	return f.crypt(
		(&statefulEncoder{}).
			Bytes(frame.Data).
			buffer,
		crypt,
		frametypes.FrameTyData,
		nonce,
	)
}

func (f *frameMarshaler) UnmarshalData(data []byte, crypt cipher.AEAD) (*frametypes.DataFrame, error) {
	plain, err := f.deCrypt(data, crypt)
	if err != nil {
		return nil, err
	}
	d := &statefulDecoder{input: plain}
	o := &frametypes.DataFrame{
		Data: d.Bytes(),
	}
	if d.Ok() {
		return o, nil
	}
	return nil, ifaces.ErrPacketMalformed
}

func (f *frameMarshaler) MarshalCtrl(frame *frametypes.CtrlFrame, nonce *uint64, crypt cipher.AEAD) []byte {
	return f.crypt(
		(&statefulEncoder{}).
			U8(uint8(frame.CtrlType)).
			U16(frame.CtrlNonce).
			Bytes(frame.Extra).
			buffer,
		crypt,
		frametypes.FrameTyCtrl,
		nonce,
	)
}

func (f *frameMarshaler) UnmarshalCtrl(data []byte, crypt cipher.AEAD) (*frametypes.CtrlFrame, error) {
	plain, err := f.deCrypt(data, crypt)
	if err != nil {
		return nil, err
	}
	d := &statefulDecoder{input: plain}
	o := &frametypes.CtrlFrame{
		CtrlType:  frametypes.CtrlType(d.U8()),
		CtrlNonce: d.U16(),
		Extra:     d.Bytes(),
	}
	if d.Ok() {
		return o, nil
	}
	return nil, ifaces.ErrPacketMalformed
}

func (f *frameMarshaler) ValidateFrameType(fr *frametypes.AbstractFrame) bool {
	return (fr.FrameType == frametypes.FrameTyHandshakeClient || fr.FrameType == frametypes.FrameTyHandshakeServer) ||
		len(fr.Body) > 12 && fr.Body[3] == uint8(fr.FrameType)
}

// --- AEAD helpers ---

// Nonce layout per spec: 0x00 || 0x00 || 0x00 || frameType (1B) || counter (uint64 LE)
// Output is the on-wire form: nonce || ciphertext || GCM tag.
// The counter pointed to by `nonce` is incremented after use.
func (f *frameMarshaler) crypt(blob []byte, crypt cipher.AEAD, frameType frametypes.FrameType, nonce *uint64) []byte {
	cNonce := make([]byte, 12, 12)
	cNonce[3] = byte(frameType)
	binary.LittleEndian.PutUint64(cNonce[4:], *nonce)
	*nonce++
	return append(
		cNonce,
		crypt.Seal(nil, cNonce, blob, nil)...,
	)
}

func (f *frameMarshaler) deCrypt(ciphertext []byte, crypt cipher.AEAD) ([]byte, error) {
	if len(ciphertext) < 12+crypt.Overhead() {
		return nil, ifaces.ErrPacketMalformed
	}
	return crypt.Open(nil, ciphertext[:12], ciphertext[12:], nil)
}
