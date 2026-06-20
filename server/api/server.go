package api

import (
	"embed"
	"os"

	"github.com/DubbaThony/share-server/app_metadata"
	"github.com/DubbaThony/share-server/ifaces"
	"github.com/labstack/echo/v4"
	"github.com/rs/zerolog"
)

type apiserver struct {
	cfg ifaces.Config
	pub ifaces.Publisher
	sf  embed.FS
	id  ifaces.Identity
	amd ifaces.AppMeta
}

func New(cfg ifaces.Config, sf embed.FS, pub ifaces.Publisher, id ifaces.Identity, l zerolog.Logger) ifaces.ApiServer {
	if fi, err := os.Stat(cfg.MapLocation()); err != nil || !fi.IsDir() {
		panic("gps data directory missing or not a directory: " + cfg.MapLocation())
	}

	return &apiserver{
		cfg: cfg,
		pub: pub,
		sf:  sf,
		id:  id,
		amd: app_metadata.New(l, sf),
	}
}

func (a *apiserver) InstallRoutes(e *echo.Echo) {
	a.installStaticRoutes(e)
	a.installApiRoutes(e)
}
