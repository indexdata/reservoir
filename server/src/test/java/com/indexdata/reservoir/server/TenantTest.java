package com.indexdata.reservoir.server;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TenantTest {

    @Test
    public void TestTenantWithTenant() {
      HttpServerRequest mockRequest = mock(HttpServerRequest.class);
      when(mockRequest.getHeader("X-Okapi-Tenant")).thenReturn("tenant1");
      when(mockRequest.getHeader("X-Okapi-Permissions")).thenReturn("[\"" + Tenant.UPLOAD_PERMISSIONS_SOURCE_PREFIX + ".my-source\"]");

      RoutingContext mockContext = mock(RoutingContext.class);
      when(mockContext.request()).thenReturn(mockRequest);

      Tenant.populate(mockContext, "tenant2");

      verify(mockContext).put("tenant", "tenant1");
      verify(mockContext).put("perms", "[\"" + Tenant.UPLOAD_PERMISSIONS_SOURCE_PREFIX + ".my-source\"]");

      when(mockContext.get("tenant")).thenReturn("tenant1");
      when(mockContext.get("perms")).thenReturn("[\"" + Tenant.UPLOAD_PERMISSIONS_SOURCE_PREFIX + ".my-source\"]");

      String tenant = Tenant.get(mockContext);
      assertEquals("tenant1", tenant);

      Tenant.ensurePermissionsSource(mockContext, "my-source");
    }

    @Test
    public void TestTenantDefaultTenant() {
      HttpServerRequest mockRequest = mock(HttpServerRequest.class);
      when(mockRequest.getHeader("X-Okapi-Tenant")).thenReturn(null);

      RoutingContext mockContext = mock(RoutingContext.class);
      when(mockContext.request()).thenReturn(mockRequest);

      Tenant.populate(mockContext, "tenant2");

      verify(mockContext).put("tenant", "tenant2");
      verify(mockContext).put("perms", "[\"" + Tenant.UPLOAD_PERMISSIONS_ALLSOURCES + "\"]");

      when(mockContext.get("tenant")).thenReturn("tenant2");
      when(mockContext.get("perms")).thenReturn("[\"" + Tenant.UPLOAD_PERMISSIONS_ALLSOURCES + "\"]");

      String tenant = Tenant.get(mockContext);
      assertEquals("tenant2", tenant);

      Tenant.ensurePermissionsSource(mockContext, "my-source");
    }

    @Test
    public void MissingTenant() {
      RoutingContext mockContext = mock(RoutingContext.class);
      when(mockContext.get("tenant")).thenReturn(null);
      try {
        Tenant.get(mockContext);
      } catch (IllegalStateException e) {
        assertEquals("X-Okapi-Tenant header is missing", e.getMessage());
      }
    }

    @Test
    public void MissingPermissionsNull() {
      RoutingContext mockContext = mock(RoutingContext.class);
      when(mockContext.get("perms")).thenReturn(null);
      try {
        Tenant.ensurePermissionsSource(mockContext, "my-source");
      } catch (ForbiddenException e) {
        assertEquals("Insufficient permissions to upload records for source 'my-source'", e.getMessage());
      }
    }

    @Test
    public void MissingPermissionsEmpty() {
      RoutingContext mockContext = mock(RoutingContext.class);
      when(mockContext.get("perms")).thenReturn("");
      try {
        Tenant.ensurePermissionsSource(mockContext, "my-source");
      } catch (ForbiddenException e) {
        assertEquals("Insufficient permissions to upload records for source 'my-source'", e.getMessage());
      }
    }

    @Test
    public void MissingPermissionsInvalid() {
      RoutingContext mockContext = mock(RoutingContext.class);
      when(mockContext.get("perms")).thenReturn("{");
      try {
        Tenant.ensurePermissionsSource(mockContext, "my-source");
      } catch (ForbiddenException e) {
        assertEquals("Cannot verify permissions to upload records for source 'my-source'", e.getMessage());
      }
    }
  }
