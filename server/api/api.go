package api

import (
	"fmt"
	"net/http"

	"github.com/DubbaThony/share-server/ifaces"
	"github.com/labstack/echo/v4"
)

func (a *apiserver) installApiRoutes(e *echo.Echo) {
	e.GET("bind/:key", a.pub.EchoHandler())
	e.GET("identity", a.getIdentity)
	e.GET("config", a.getConfig)
	e.GET("gpdr-email", a.getGpdrEmail)
}

func (a *apiserver) getIdentity(c echo.Context) error {
	return c.JSON(http.StatusOK, ifaces.BasicResponse[string]{
		Status: true,
		Result: fmt.Sprintf("0x%X", a.id.Identity()),
	})
}
func (a *apiserver) getConfig(c echo.Context) error {
	return c.JSON(http.StatusOK, ifaces.BasicResponse[ifaces.AppConfig]{
		Status: true,
		Result: ifaces.AppConfig{
			Identity:              fmt.Sprintf("0x%X", a.id.Identity()),
			PrefferedAppBuildHash: a.amd.Hash(),
			LocalSigner:           a.amd.Signer(),
		},
	})
}
func (a *apiserver) getGpdrEmail(c echo.Context) error {
	return c.JSON(http.StatusOK, ifaces.BasicResponse[string]{
		Status: true,
		Result: a.cfg.ComplianceEmailAddr(),
	})
}
