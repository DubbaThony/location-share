package api

import (
	"os"

	"github.com/labstack/echo/v4"
)

func (a *apiserver) installStaticRoutes(e *echo.Echo) {
	// FE
	e.GET("/static/*", a.serveStatic())
	e.GET("/", a.serveIndex())
	e.GET("/index.html", a.serveIndex())
	e.GET("/index.htm", a.serveIndex())
	e.GET("/map/*", a.serveMap())
}

func (a *apiserver) serveIndex() echo.HandlerFunc {
	// wrap function to not instantiate handlers every call.
	staticRoot := echo.MustSubFS(a.sf, "static")
	serveIndex := echo.StaticFileHandler("index.html", staticRoot)
	serveView := echo.StaticFileHandler("view.html", staticRoot)
	serveExpired := echo.StaticFileHandler("expired.html", staticRoot)
	return func(c echo.Context) error {
		key := c.QueryParam("id")
		if key == "" {
			return serveIndex(c)
		} else if a.pub.Exists(key) {
			return serveView(c)
		} else {
			return serveExpired(c)
		}
	}
}

func (a *apiserver) serveStatic() echo.HandlerFunc {
	staticRoot := echo.MustSubFS(a.sf, "static")
	return echo.StaticDirectoryHandler(staticRoot, false)
}

func (a *apiserver) serveMap() echo.HandlerFunc {
	return echo.StaticDirectoryHandler(os.DirFS(a.cfg.MapLocation()), false)
}
