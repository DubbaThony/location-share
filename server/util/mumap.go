package util

import "sync"

// MuMap is a generic concurrent map with a global RW mutex for structural
// changes (key insert/delete) and a per-entry RW mutex for value access.
// Bundling the per-entry mutex with the value into a single heap entry kept
// behind one pointer in the inner map means a goroutine can capture the entry
// under gMut, release gMut, and proceed to lock the entry mutex — the entry
// struct stays alive on the heap as long as someone holds a reference, even
// after Delete removes it from the inner map.
//
// Calling any MuMap method from inside a callback will deadlock. Don't.
type MuMap[kt comparable, vt any] struct {
	gMut  sync.RWMutex
	inner map[kt]*muMapEntry[vt]
}

type muMapEntry[vt any] struct {
	val *vt
	mu  sync.RWMutex
}

func NewMuMap[kt comparable, vt any]() *MuMap[kt, vt] {
	return &MuMap[kt, vt]{
		inner: make(map[kt]*muMapEntry[vt]),
	}
}

func (m *MuMap[kt, vt]) Read(k kt, cb func(v *vt)) bool {
	m.gMut.RLock()
	e, ok := m.inner[k]
	m.gMut.RUnlock()
	if !ok {
		return false
	}
	e.mu.RLock()
	defer e.mu.RUnlock()
	cb(e.val)
	return true
}

func (m *MuMap[kt, vt]) Update(k kt, cb func(v *vt)) bool {
	m.gMut.RLock()
	e, ok := m.inner[k]
	m.gMut.RUnlock()
	if !ok {
		return false
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	cb(e.val)
	return true
}

func (m *MuMap[kt, vt]) Has(k kt) bool {
	m.gMut.RLock()
	defer m.gMut.RUnlock()
	_, ok := m.inner[k]
	return ok
}

func (m *MuMap[kt, vt]) Set(k kt, v *vt) {
	m.gMut.Lock()
	defer m.gMut.Unlock()
	e, ok := m.inner[k]
	if !ok {
		m.inner[k] = &muMapEntry[vt]{val: v}
		return
	}
	e.mu.Lock()
	e.val = v
	e.mu.Unlock()
}

func (m *MuMap[kt, vt]) Delete(k kt) {
	m.gMut.Lock()
	defer m.gMut.Unlock()
	delete(m.inner, k)
}

func (m *MuMap[kt, vt]) Foreach(f func(k kt, v *vt)) {
	m.gMut.RLock()
	defer m.gMut.RUnlock()
	for k, e := range m.inner {
		e.mu.Lock()
		f(k, e.val)
		e.mu.Unlock()
	}
}
