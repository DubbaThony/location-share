// Command vectordump emits cross-language protocol test vectors as JSON.
// The Kotlin port's test suite loads these and asserts byte-for-byte equality,
// so the Go marshaller stays the single source of truth.
//
//	cd server && go run ./cmd/vectordump > ../client/Android/protocol/src/test/resources/testvectors.json
package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/sha512"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"

	"github.com/DubbaThony/share-server/protocol/frametypes"
	"github.com/DubbaThony/share-server/protocol/marshaller"
)

func h(b []byte) string { return hex.EncodeToString(b) }

func u64hex(v uint64) string { return fmt.Sprintf("%016x", v) }

func leU64(v uint64) []byte {
	b := make([]byte, 8)
	binary.LittleEndian.PutUint64(b, v)
	return b
}

// fixed 32-byte AES key (bytes 0..31) shared by all encrypted vectors
func fixedKey() []byte {
	k := make([]byte, 32)
	for i := range k {
		k[i] = byte(i)
	}
	return k
}

func main() {
	m := marshaller.New()
	key := fixedKey()
	block, err := aes.NewCipher(key)
	if err != nil {
		panic(err)
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		panic(err)
	}

	// 65-byte placeholder "ECDH" payload for handshake marshalling (not a real point)
	ecdh65 := make([]byte, 65)
	ecdh65[0] = 0x04
	for i := 1; i < 65; i++ {
		ecdh65[i] = byte(i - 1)
	}
	// 64-byte placeholder Ed25519 signature for handshake-server marshalling.
	// Distinct byte pattern from ecdh65 so a mixed-up offset bug surfaces
	// loudly in the cross-language test.
	sig64 := make([]byte, 64)
	for i := range sig64 {
		sig64[i] = byte(i + 0x80)
	}

	out := map[string]any{}

	// --- plaintext outer envelope ---
	absCases := []*frametypes.AbstractFrame{
		{ConnID: 0x0102030405060708, FrameType: frametypes.FrameTyData, Body: []byte{0xDE, 0xAD, 0xBE, 0xEF}},
		{ConnID: 0, FrameType: frametypes.FrameTyHandshakeClient, Body: []byte{}},
		{ConnID: 0xFFFFFFFFFFFFFFFF, FrameType: frametypes.FrameTyCtrl, Body: []byte{0, 1, 2, 3, 4, 5}},
	}
	var absVecs []any
	for _, f := range absCases {
		absVecs = append(absVecs, map[string]any{
			"connIdHex":   u64hex(f.ConnID),
			"frameType":   int(f.FrameType),
			"bodyHex":     h(f.Body),
			"expectedHex": h(m.MarshalAbstractFrame(f)),
		})
	}
	out["abstractFrame"] = absVecs

	// --- plaintext handshakes ---
	hc := &frametypes.ClientHandshakeFrame{ECDH: [65]byte(ecdh65), ProtoVersion: 1}
	out["handshakeClient"] = []any{map[string]any{
		"ecdhHex":      h(ecdh65),
		"protoVersion": int(hc.ProtoVersion),
		"expectedHex":  h(m.MarshalHandshakeClient(hc)),
	}}

	hs := &frametypes.ServerHandshakeFrame{
		ECDH:         [65]byte(ecdh65),
		Signature:    [64]byte(sig64),
		Status:       frametypes.HandshakeStatusOK,
		ConnectionID: 0x1122334455667788,
	}
	out["handshakeServer"] = []any{map[string]any{
		"ecdhHex":         h(ecdh65),
		"signatureHex":    h(sig64),
		"status":          int(hs.Status),
		"connectionIdHex": u64hex(hs.ConnectionID),
		"expectedHex":     h(m.MarshalHandshakeServer(hs)),
	}}

	// --- encrypted frames (fixed key, fixed counters) ---
	encVecs := map[string]any{}

	c0 := uint64(0)
	encVecs["encryptedHandshakeClient"] = map[string]any{
		"counter":     0,
		"featFlag":    1,
		"expectedHex": h(m.MarshalEncryptedHandshakeClient(&frametypes.EncryptedClientHandshakeFrame{FeatFlag: 1}, &c0, aead)),
	}

	alias := []byte("abcdefghijklmnopqrstuvwxyz012345") // 32 bytes
	c1 := uint64(1)
	encVecs["encryptedHandshakeServer"] = map[string]any{
		"counter":     1,
		"featFlag":    1,
		"aliasHex":    h(alias),
		"expectedHex": h(m.MarshalEncryptedHandshakeServer(&frametypes.EncryptedServerHandshakeFrame{FeatFlag: 1, PublicAlias: [32]byte(alias)}, &c1, aead)),
	}

	c2 := uint64(2)
	encVecs["ping"] = map[string]any{
		"counter":     2,
		"nonce":       0xBEEF,
		"expectedHex": h(m.MarshalPing(&frametypes.PingFrame{Nonce: 0xBEEF}, &c2, aead)),
	}

	c3 := uint64(3)
	dataPayload := []byte{0x01, 0x02, 0x03}
	encVecs["data"] = map[string]any{
		"counter":     3,
		"dataHex":     h(dataPayload),
		"expectedHex": h(m.MarshalData(&frametypes.DataFrame{Data: dataPayload}, &c3, aead)),
	}

	c4 := uint64(4)
	encVecs["ctrl"] = map[string]any{
		"counter":     4,
		"ctrlType":    int(frametypes.CtrlTypeACK),
		"ctrlNonce":   0x1234,
		"extraHex":    "",
		"expectedHex": h(m.MarshalCtrl(&frametypes.CtrlFrame{CtrlType: frametypes.CtrlTypeACK, CtrlNonce: 0x1234, Extra: []byte{}}, &c4, aead)),
	}
	out["keyHex"] = h(key)
	out["encrypted"] = encVecs

	// --- KDF (fixed shared secret, isolates SHA-512 split) ---
	shared := make([]byte, 32)
	for i := range shared {
		shared[i] = 0xAA
	}
	srvConn := uint64(0x1111111111111111)
	cliConn := uint64(0x2222222222222222)
	material := append(append(append([]byte{}, shared...), leU64(srvConn)...), leU64(cliConn)...)
	kdf := sha512.Sum512(material)
	out["kdf"] = []any{map[string]any{
		"sharedSecretHex": h(shared),
		"serverConnIdHex": u64hex(srvConn),
		"clientConnIdHex": u64hex(cliConn),
		"k1Hex":           h(kdf[:32]),
		"k2Hex":           h(kdf[32:]),
	}}

	// --- ECDH end-to-end (fixed scalars -> pubkeys -> shared -> derived keys) ---
	srvScalar := bytesRepeat(0x11, 32)
	cliScalar := bytesRepeat(0x22, 32)
	srvPriv, err := ecdh.P256().NewPrivateKey(srvScalar)
	if err != nil {
		panic(err)
	}
	cliPriv, err := ecdh.P256().NewPrivateKey(cliScalar)
	if err != nil {
		panic(err)
	}
	srvPub := srvPriv.PublicKey().Bytes()
	cliPub := cliPriv.PublicKey().Bytes()
	sharedSecret, err := srvPriv.ECDH(cliPriv.PublicKey())
	if err != nil {
		panic(err)
	}
	material2 := append(append(append([]byte{}, sharedSecret...), leU64(srvConn)...), leU64(cliConn)...)
	kdf2 := sha512.Sum512(material2)
	out["ecdh"] = []any{map[string]any{
		"serverScalarHex": h(srvScalar),
		"clientScalarHex": h(cliScalar),
		"serverPubHex":    h(srvPub),
		"clientPubHex":    h(cliPub),
		"serverConnIdHex": u64hex(srvConn),
		"clientConnIdHex": u64hex(cliConn),
		"sharedHex":       h(sharedSecret),
		"k1Hex":           h(kdf2[:32]),
		"k2Hex":           h(kdf2[32:]),
	}}

	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	if err := enc.Encode(out); err != nil {
		panic(err)
	}
}

func bytesRepeat(b byte, n int) []byte {
	out := make([]byte, n)
	for i := range out {
		out[i] = b
	}
	return out
}
