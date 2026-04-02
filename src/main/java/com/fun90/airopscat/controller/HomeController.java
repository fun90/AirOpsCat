package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.vo.ConsolePage;
import com.fun90.airopscat.service.ConsolePageRegistry;
import io.quarkus.qute.Engine;
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
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
@Path("/")
public class HomeController {

    @ConfigProperty(name = "quarkus.application.name", defaultValue = "AirOpsCat")
    String appName;

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "dev")
    String appVersion;

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
        Template pageTemplate = engine.getTemplate(page.contentTemplate());
        if (pageTemplate == null) {
            return notFound.instance().render();
        }
        return pageTemplate.instance().data(buildTemplateData(page)).render();
    }

    private Map<String, Object> buildTemplateData(ConsolePage page) {
        Map<String, Object> data = new HashMap<>();
        data.put("appName", appName);
        data.put("appVersion", appVersion);
        data.put("currentModuleKey", page.moduleKey());
        data.put("menuGroups", pageRegistry.getMenuGroups());
        data.put("moduleTitle", page.moduleTitle());
        data.put("pageTitle", page.title());
        data.put("pageSecondaryTitle", page.secondaryTitle());
        data.put("uri", page.uri());
        data.put("showAddButton", page.showAddButton());
        data.put("requiresTomSelect", page.requiresTomSelect());
        data.put("requiresCharts", page.requiresCharts());
        data.put("buttonText", page.buttonText());
        data.put("modalIdPrefix", page.modalIdPrefix());
        return data;
    }
}
