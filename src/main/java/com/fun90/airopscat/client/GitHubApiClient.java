package com.fun90.airopscat.client;

import com.fun90.airopscat.model.dto.GitHubReleaseDto;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.concurrent.CompletionStage;

/**
 * GitHub API REST 客户端
 */
@RegisterRestClient(baseUri = "https://api.github.com")
@Path("/repos/fun90/AirOpsCat/releases")
@Produces(MediaType.APPLICATION_JSON)
public interface GitHubApiClient {
    
    /**
     * 获取最新发布版本
     */
    @GET
    @Path("/latest")
    CompletionStage<GitHubReleaseDto> getLatestRelease();
}