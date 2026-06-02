package marshaller

import "encoding/binary"

type statefulEncoder struct {
	buffer  []byte
	scratch [8]byte // prevent useless alloc
}

func (s *statefulEncoder) U8(val uint8) *statefulEncoder {
	s.buffer = append(s.buffer, byte(val))
	return s
}

func (s *statefulEncoder) U16(val uint16) *statefulEncoder {
	binary.LittleEndian.PutUint16(s.scratch[:], val)
	s.buffer = append(s.buffer, s.scratch[0:2]...)
	return s
}

func (s *statefulEncoder) U32(val uint32) *statefulEncoder {
	binary.LittleEndian.PutUint32(s.scratch[:], val)
	s.buffer = append(s.buffer, s.scratch[0:4]...)
	return s
}

func (s *statefulEncoder) U64(val uint64) *statefulEncoder {
	binary.LittleEndian.PutUint64(s.scratch[:], val)
	s.buffer = append(s.buffer, s.scratch[0:8]...)
	return s
}

func (s *statefulEncoder) Bytes(val []byte) *statefulEncoder {
	s.U16(uint16(len(val)))
	s.BytesNoHeader(val)
	return s
}

func (s *statefulEncoder) BytesNoHeader(val []byte) *statefulEncoder {
	s.buffer = append(s.buffer, val...)
	return s
}

type statefulDecoder struct {
	input []byte
	ptr   uint16
	fail  bool
}

func (s *statefulDecoder) U8() uint8 {
	if !s.assertDataLeft(1) {
		return 0
	}
	value := s.input[s.ptr]
	s.ptr++
	return value
}

func (s *statefulDecoder) U16() uint16 {
	if !s.assertDataLeft(2) {
		return 0
	}
	out := binary.LittleEndian.Uint16(s.input[s.ptr : s.ptr+2])
	s.ptr += 2
	return out
}

func (s *statefulDecoder) U32() uint32 {
	if !s.assertDataLeft(4) {
		return 0
	}
	out := binary.LittleEndian.Uint32(s.input[s.ptr : s.ptr+4])
	s.ptr += 4
	return out
}

func (s *statefulDecoder) U64() uint64 {
	if !s.assertDataLeft(8) {
		return 0
	}
	out := binary.LittleEndian.Uint64(s.input[s.ptr : s.ptr+8])
	s.ptr += 8
	return out
}

func (s *statefulDecoder) Bytes() []byte {
	l := s.U16()
	if l == 0 {
		return make([]byte, 0, 0)
	}
	return s.BytesNoHeader(l)
}

func (s *statefulDecoder) BytesNoHeader(len uint16) []byte {
	if !s.assertDataLeft(len) {
		return make([]byte, len, len)
	}
	out := s.input[s.ptr : s.ptr+len]
	s.ptr += len
	return out
}

func (s *statefulDecoder) Ok() bool {
	return !s.fail && s.ptr == uint16(len(s.input))
}

func (s *statefulDecoder) assertDataLeft(size uint16) bool {
	if s.fail {
		return false
	}
	if s.ptr+size > uint16(len(s.input)) {
		s.fail = true
		return false
	}
	return true
}
