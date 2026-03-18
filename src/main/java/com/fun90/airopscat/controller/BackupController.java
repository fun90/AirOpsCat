package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.BackupFileDto;
import com.fun90.airopscat.service.DatabaseBackupService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.File;
import java.util.Map;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

@ApplicationScoped
@Path("/api/admin/backups")
@RolesAllowed("ADMIN")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BackupController {

    @Inject
    DatabaseBackupService databaseBackupService;

    @GET
    public Response getBackupPage(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size
    ) {
        return Response.ok(databaseBackupService.getBackupPage(page, size)).build();
    }

    @POST
    @Path("/run")
    public Response runBackup() {
        BackupFileDto backupFile = databaseBackupService.createBackup();
        return Response.ok(backupFile).build();
    }

    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadBackup(@RestForm("file") FileUpload fileUpload) {
        if (fileUpload == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", "Please select a backup file"))
                    .build();
        }

        BackupFileDto backupFile = databaseBackupService.uploadBackup(fileUpload.fileName(), fileUpload.uploadedFile());
        return Response.ok(backupFile).build();
    }

    @GET
    @Path("/{fileName}/download")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response downloadBackup(@PathParam("fileName") String fileName) {
        File file = databaseBackupService.getBackupFile(fileName);
        return Response.ok(file)
                .header("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"")
                .build();
    }

    @POST
    @Path("/{fileName}/restore")
    public Response restoreBackup(@PathParam("fileName") String fileName) {
        databaseBackupService.restoreBackup(fileName);
        return Response.ok(Map.of("message", "backup restored", "fileName", fileName)).build();
    }

    @DELETE
    @Path("/{fileName}")
    public Response deleteBackup(@PathParam("fileName") String fileName) {
        databaseBackupService.deleteBackup(fileName);
        return Response.ok().build();
    }
}
