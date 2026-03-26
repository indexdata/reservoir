package com.indexdata.reservoir.server;

import io.vertx.ext.web.RoutingContext;
import java.util.regex.Pattern;
import org.folio.okapi.common.XOkapiHeaders;

public final class Tenant {
  private static final String TENANT_PATTERN_STRING =  "^[_a-z][_a-z0-9]*$";
  private static final Pattern TENANT_PATTERN = Pattern.compile(TENANT_PATTERN_STRING);
  private static final String CTX_KEY_TENANT = "tenant";
  private static final String CTX_KEY_PERMS = "perms";

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
    if (tenant != null) {
      ctx.put(CTX_KEY_TENANT, tenant);
      ctx.put(CTX_KEY_PERMS, true);
    } else if (defaultTenant != null) {
      ctx.put(CTX_KEY_TENANT, defaultTenant);
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

  public static boolean enforcePermissions(RoutingContext ctx) {
    Boolean perms = ctx.get(CTX_KEY_PERMS);
    return perms != null && perms;
  }
}