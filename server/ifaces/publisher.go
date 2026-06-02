package ifaces

// Publisher is api side
type Publisher interface {
	Create(uint64, string) error
	Push(uint64, []byte) error
	CountSubs(uint64) (uint64, error)
	Teardown(uint64)
	Exists(key string) bool
}
