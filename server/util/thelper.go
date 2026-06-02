package util

import (
	"container/heap"
	"sync"
	"time"
)

// THelper is a merge clock: many scheduled values come in via Push, they fall
// out on C in deadline order. Push deadlines are wallclock-style — Now() + d
// at the moment of the call.
//
// The scheduler goroutine starts on demand at the first Push and shuts itself
// down once the queue drains. A subsequent Push restarts it. No idle goroutine
// when there's nothing to do.
//
// All methods are safe for concurrent use.
//
// Close shuts the helper down and closes C. Pushes after Close are silently
// dropped. Calling Close twice is safe.
type THelper[T any] struct {
	C       chan T
	mu      sync.Mutex
	queue   thelperHeap[T]
	running bool
	closed  bool
	wakeup  chan struct{}
	quit    chan struct{}
	wg      sync.WaitGroup
}

type thelperItem[T any] struct {
	deadline time.Time
	value    T
}

func NewTHelper[T any]() *THelper[T] {
	return &THelper[T]{
		C:      make(chan T),
		wakeup: make(chan struct{}, 1),
		quit:   make(chan struct{}),
	}
}

func (h *THelper[T]) Push(d time.Duration, value T) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.closed {
		return
	}
	heap.Push(&h.queue, thelperItem[T]{deadline: time.Now().Add(d), value: value})
	if h.running {
		// Nudge the loop in case the new deadline is earlier than what it's sleeping on.
		select {
		case h.wakeup <- struct{}{}:
		default:
		}
		return
	}
	h.running = true
	h.wg.Add(1)
	go h.loop()
}

func (h *THelper[T]) Close() {
	h.mu.Lock()
	if h.closed {
		h.mu.Unlock()
		return
	}
	h.closed = true
	h.mu.Unlock()
	close(h.quit)
	h.wg.Wait()
	close(h.C)
}

func (h *THelper[T]) loop() {
	defer h.wg.Done()
	for {
		h.mu.Lock()
		if h.queue.Len() == 0 {
			h.running = false
			h.mu.Unlock()
			return
		}
		topDeadline := h.queue[0].deadline
		h.mu.Unlock()

		d := time.Until(topDeadline)
		if d > 0 {
			timer := time.NewTimer(d)
			select {
			case <-h.quit:
				timer.Stop()
				return
			case <-h.wakeup:
				timer.Stop()
				continue
			case <-timer.C:
				// fall through to delivery
			}
		}

		h.mu.Lock()
		if h.queue.Len() == 0 {
			h.mu.Unlock()
			continue
		}
		top := heap.Pop(&h.queue).(thelperItem[T])
		h.mu.Unlock()

		select {
		case h.C <- top.value:
		case <-h.quit:
			return
		}
	}
}

// --- heap implementation ---

type thelperHeap[T any] []thelperItem[T]

func (h thelperHeap[T]) Len() int           { return len(h) }
func (h thelperHeap[T]) Less(i, j int) bool { return h[i].deadline.Before(h[j].deadline) }
func (h thelperHeap[T]) Swap(i, j int)      { h[i], h[j] = h[j], h[i] }
func (h *thelperHeap[T]) Push(x any)        { *h = append(*h, x.(thelperItem[T])) }
func (h *thelperHeap[T]) Pop() any {
	old := *h
	n := len(old)
	x := old[n-1]
	*h = old[:n-1]
	return x
}
