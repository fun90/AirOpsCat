package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.vo.ConsolePage;
import com.fun90.airopscat.service.ConsolePageRegistry;
import io.quarkus.qute.Engine;
import io.quarkus.qute.RawString;
import io.quarkus.qute.Template;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;

@ApplicationScoped
@Path("/")
public class HomeController {

    @ConfigProperty(name = "quarkus.application.name", defaultValue = "AirOpsCat")
    String appName;

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "dev")
    String appVersion;

    @Inject
    Template layout;

    @Inject
    Template notFound;

    @Inject
    Engine engine;

    @Inject
    ConsolePageRegistry pageRegistry;

    @GET
    @Path("/dashboard")
    @Produces(MediaType.TEXT_HTML)
    @Authenticated
    public String dashboard() {
        return renderPage(pageRegistry.getDashboardPage().withShowAddButton(false));
    }

    @GET
    @Path("/")
    public Response rootRedirect() {
        return Response.status(302).location(URI.create("/dashboard")).build();
    }

    @GET
    @Path("/console/{module}/{page}")
    @Produces(MediaType.TEXT_HTML)
    @Authenticated
    public String console(@PathParam("module") String module, @PathParam("page") String page) {
        String uri = "/" + module + "/" + page;
        ConsolePage consolePage = pageRegistry.getPage(uri);
        if (consolePage == null) {
            return notFound.instance().render();
        }
        return renderPage(consolePage);
    }

    @GET
    @Path("/api/admin/data")
    @RolesAllowed("ADMIN")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getAdminData() {
        return Response.ok("This is admin data, only accessible to admins").build();
    }

    @GET
    @Path("/api/partner/data")
    @RolesAllowed({"ADMIN", "PARTNER"})
    @Produces(MediaType.TEXT_PLAIN)
    public Response getPartnerData() {
        return Response.ok("This is partner data, accessible to partners and admins").build();
    }

    @GET
    @Path("/api/vip/data")
    @RolesAllowed({"ADMIN", "PARTNER", "VIP"})
    @Produces(MediaType.TEXT_PLAIN)
    public Response getVipData() {
        return Response.ok("This is VIP data, accessible to VIPs, partners, and admins").build();
    }

    private String renderPage(ConsolePage page) {
        Template contentTemplate = engine.getTemplate(page.contentTemplate());
        if (contentTemplate == null) {
            return notFound.instance().render();
        }

        String pageContent = contentTemplate.instance()
                .data("appName", appName)
                .data("moduleTitle", page.moduleTitle())
                .data("pageTitle", page.title())
                .data("pageSecondaryTitle", page.secondaryTitle())
                .data("uri", page.uri())
                .data("showAddButton", page.showAddButton())
                .data("buttonText", page.buttonText())
                .data("modalIdPrefix", page.modalIdPrefix())
                .render();

        return layout.data("appName", appName)
                .data("appVersion", appVersion)
                .data("currentModuleKey", page.moduleKey())
                .data("menuGroups", pageRegistry.getMenuGroups())
                .data("moduleTitle", page.moduleTitle())
                .data("pageTitle", page.title())
                .data("pageSecondaryTitle", page.secondaryTitle())
                .data("uri", page.uri())
                .data("showAddButton", page.showAddButton())
                .data("buttonText", page.buttonText())
                .data("modalIdPrefix", page.modalIdPrefix())
                .data("pageContent", new RawString(pageContent))
                .render();
    }
}
