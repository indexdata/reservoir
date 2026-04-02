package com.indexdata.reservoir.server;

import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.RoutingContext;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.folio.okapi.common.XOkapiHeaders;

public final class Tenant {
  private static final String TENANT_PATTERN_STRING =  "^[_a-z][_a-z0-9]*$";
  private static final Pattern TENANT_PATTERN = Pattern.compile(TENANT_PATTERN_STRING);
  private static final String CTX_KEY_TENANT = "tenant";
  private static final String CTX_KEY_PERMS = "perms";
  private static final String UPLOAD_PERMISSIONS_ALLSOURCES = "reservoir-upload.all-sources";
  private static final String UPLOAD_PERMISSIONS_SOURCE_PREFIX = "reservoir-upload.source";

  private Tenant() {
    // utility class
  }

  /**
   * Populate the tenant in the context from the X-Okapi-Tenant header.
   * @param ctx routing context
   * @param defaultTenant default tenant value
   */
  public static void populate(RoutingContext ctx, String defaultTenant) {
    String tenant = ctx.request().getHeader(XOkapiHeaders.TENANT);
    if (tenant == null && defaultTenant != null) {
      ctx.put(CTX_KEY_TENANT, defaultTenant);
      ctx.put(CTX_KEY_PERMS, "[\"" + UPLOAD_PERMISSIONS_ALLSOURCES + "\"]");
    } else {
      ctx.put(CTX_KEY_TENANT, tenant);
      ctx.put(CTX_KEY_PERMS, ctx.request().getHeader(XOkapiHeaders.PERMISSIONS));
    }
  }

  /**
   * Get the tenant from the context.
   * The tenant must have been populated by a previous call to {@link #populate}.
   * @param ctx routing context
   * @return tenant value
   */
  public static String get(RoutingContext ctx) {
    String tenant = ctx.get(CTX_KEY_TENANT);
    if (tenant == null) {
      throw new IllegalStateException(XOkapiHeaders.TENANT + " header is missing");
    }
    if (! TENANT_PATTERN.matcher(tenant).find()) {
      throw new IllegalArgumentException(
         XOkapiHeaders.TENANT + " header must match " + TENANT_PATTERN_STRING);
    }
    return tenant;
  }

  /**
   * Check if the tenant has permissions for a specific source.
   * The tenant must have been populated by a previous call to {@link #populate}.
   * @param ctx routing context
   * @param sourceId source identifier
   * @throws ForbiddenException if the tenant does not have the required permissions
   */
  public static void ensurePermissionsSource(RoutingContext ctx, String sourceId)
      throws ForbiddenException {
    try {
      Set<String> perms = parsePermissions(ctx);
      if (perms.contains(UPLOAD_PERMISSIONS_ALLSOURCES)) {
        return;
      }
      String perm = UPLOAD_PERMISSIONS_SOURCE_PREFIX + "." + sourceId;
      if (perms.contains(perm)) {
        return;
      }
    } catch (Exception e) {
      throw new ForbiddenException("Cannot verify permissions to upload records for source '"
      + sourceId + "'", e);
    }
    throw new ForbiddenException("Insufficient permissions to upload records for source '"
        + sourceId + "'");
  }

  @SuppressWarnings("unchecked")
  private static Set<String> parsePermissions(RoutingContext ctx) {
    Set<String> perms = new HashSet<>();
    String permsHeader = ctx.get(CTX_KEY_PERMS);
    if (permsHeader == null || permsHeader.isEmpty()) {
      return perms;
    }
    JsonArray permsArray = new JsonArray(permsHeader);
    perms.addAll(permsArray.getList());
    return perms;
  }
}
