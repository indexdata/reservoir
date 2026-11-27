package com.indexdata.reservoir.server;

import com.indexdata.reservoir.server.entity.CodeModuleEntity;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.common.WebClientFactory;

/**
 * Fetches remote script content for URL-backed {@link CodeModuleEntity} definitions.
 */
public final class CodeModuleResolver {

  private static final Logger log = LogManager.getLogger(CodeModuleResolver.class);
  private static final String DEFAULT_RAW_PREFIX = "https://raw.githubusercontent.com/";
  private static final String DEFAULT_API_PREFIX = "https://api.github.com/";

  private CodeModuleResolver() { }

  // Transform https://raw.githubusercontent.com/... URLs to api.github.com to avoid caching issues.
  static String transformToApiFetch(String url, String rawPrefix, String apiPrefix) {
    if (url.startsWith(rawPrefix)) {
      String[] parts = url.substring(rawPrefix.length()).split("/", 4);
      if (parts.length == 4) {
        return String.format("%srepos/%s/%s/contents/%s?ref=%s",
            apiPrefix, parts[0], parts[1], parts[3], parts[2]);
      }
    }
    return url;
  }

  /**
   * Resolve a code module's remote URL into inline script content if a URL is configured.
   */
  public static Future<CodeModuleEntity> resolve(Vertx vertx, CodeModuleEntity module) {
    return resolve(vertx, module, DEFAULT_RAW_PREFIX, DEFAULT_API_PREFIX);
  }

  static Future<CodeModuleEntity> resolve(Vertx vertx, CodeModuleEntity module,
      String rawPrefix, String apiPrefix) {
    String id = module.id() != null ? module.id() : "none";
    String url = module.url();
    if (url == null || url.isEmpty()) {
      return Future.succeededFuture(module);
    }
    WebClient webClient = WebClientFactory.getWebClient(vertx);
    String fetchUrl = transformToApiFetch(url, rawPrefix, apiPrefix);
    log.info("Fetching module '{}' from URL {}", id, fetchUrl);
    return webClient.getAbs(fetchUrl)
        .send()
        .map(response -> {
          if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException(
                String.format("Config error: cannot retrieve module '%s' at %s (%d)",
                    id, url, response.statusCode()));
          }
          if (fetchUrl.startsWith(apiPrefix)) {
            JsonObject bodyObject = response.bodyAsJsonObject();
            String sha = bodyObject.getString("sha");
            if (sha == null) {
              throw new RuntimeException(
                  String.format("Config error: cannot retrieve module '%s' at %s: "
                      + "no sha field in API response", id, fetchUrl));
            }
            log.info("Module {} SHA {}", id, sha);
            String content = bodyObject.getString("content");
            if (content == null) {
              throw new RuntimeException(
                  String.format("Config error: cannot retrieve module '%s' at %s: "
                      + "no content field in API response", id, fetchUrl));
            }
            byte[] decodedBytes = Base64.getMimeDecoder().decode(content);
            return module.withScript(new String(decodedBytes, StandardCharsets.UTF_8));
          }
          return module.withScript(response.bodyAsString());
        });
  }
}
