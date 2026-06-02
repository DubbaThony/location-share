package util

import (
	"crypto/rand"
	"errors"
	"fmt"
	"unsafe"
)

type SInts interface {
	~int | ~int8 | ~int16 | ~int32 | ~int64
}
type UInts interface {
	~uint | ~uint8 | ~uint16 | ~uint32 | ~uint64
}
type Ints interface {
	SInts | UInts
}

func CSPRNGInt[T Ints]() T {
	var o T
	s := unsafe.Sizeof(o)
	p := unsafe.Pointer(&o)
	buff := unsafe.Slice((*byte)(p), s)
	n, err := rand.Read(buff)
	if n != int(s) && err == nil {
		err = errors.New(fmt.Sprintf("n expected to be %d but got %d", s, n))
	}
	if err != nil {
		panic("fail to CSPRNG! entropy exhausted system-wide? " + err.Error())
	}
	return o
}

// CSPRNGString returns a `length`-byte string drawn uniformly from [a-zA-Z0-9].
// Uses rejection sampling on raw CSPRNG bytes to avoid the modulo bias that
// `b % 62` would introduce. Bytes >= 248 (= 4*62) are discarded; the rest
// map 4-to-1 onto the 62-char alphabet uniformly.
func CSPRNGString(length uint16) string {
	const csprngAlphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	out := make([]byte, length)
	buf := make([]byte, int(length)*2+8) // overshoot — rejection rate ~3%
	filled := uint16(0)
	for filled < length {
		n, err := rand.Read(buf)
		if err != nil || n != len(buf) {
			panic(fmt.Sprintf("fail to CSPRNG! err=%v n=%d", err, n))
		}
		for _, b := range buf {
			if b >= 248 {
				continue
			}
			out[filled] = csprngAlphabet[b%62]
			filled++
			if filled == length {
				break
			}
		}
	}
	return string(out)
}

func P[t any](v t) *t {
	return &v
}
