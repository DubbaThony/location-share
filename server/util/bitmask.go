package util

type UnsignedInt interface {
	~uint8 | ~uint16 | ~uint32 | ~uint64
}

type Bitmask[T UnsignedInt] struct {
	val T
}

func NewBitmask[T UnsignedInt](initial T) *Bitmask[T] {
	return &Bitmask[T]{val: initial}
}

func (b *Bitmask[T]) Has(bit uint8) bool {
	return b.val&(T(1)<<bit) != 0
}

func (b *Bitmask[T]) Get() T {
	return b.val
}

func (b *Bitmask[T]) Set(bit uint8, val bool) {
	mask := T(1) << bit
	if val {
		b.val |= mask
	} else {
		b.val &^= mask
	}
}
