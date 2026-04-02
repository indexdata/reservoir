package com.indexdata.reservoir.server;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.RoutingContext;
import org.folio.okapi.common.XOkapiHeaders;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantTest {

  @Test
  void withTenant() {
    HttpServerRequest mockRequest = mock(HttpServerRequest.class);
    when(mockRequest.getHeader(XOkapiHeaders.TENANT)).thenReturn("tenant1");

    JsonArray perms = new JsonArray()
      .add("reservoir-upload.source.other-source")
      .add("reservoir-upload.source.my-source");

    when(mockRequest.getHeader(XOkapiHeaders.PERMISSIONS)).thenReturn(perms.encode());

    RoutingContext mockContext = mock(RoutingContext.class);
    when(mockContext.request()).thenReturn(mockRequest);

    Tenant.populate(mockContext, "tenant2");

    verify(mockContext).put("tenant", "tenant1");
    verify(mockContext).put("perms", perms.encode());

    when(mockContext.get("tenant")).thenReturn("tenant1");
    when(mockContext.get("perms")).thenReturn(perms.encode());

    String tenant = Tenant.get(mockContext);
    assertEquals("tenant1", tenant);

    Tenant.ensurePermissionsSource(mockContext, "my-source");
  }

  @Test
  void defaultTenant() {
    HttpServerRequest mockRequest = mock(HttpServerRequest.class);
    when(mockRequest.getHeader(XOkapiHeaders.TENANT)).thenReturn(null);

    RoutingContext mockContext = mock(RoutingContext.class);
    when(mockContext.request()).thenReturn(mockRequest);

    Tenant.populate(mockContext, "tenant2");

    JsonArray perms = new JsonArray().add("reservoir-upload.all-sources");
    verify(mockContext).put("tenant", "tenant2");
    verify(mockContext).put("perms", perms.encode());

    when(mockContext.get("tenant")).thenReturn("tenant2");
    when(mockContext.get("perms")).thenReturn(perms.encode());

    String tenant = Tenant.get(mockContext);
    assertEquals("tenant2", tenant);

    Tenant.ensurePermissionsSource(mockContext, "my-source");
  }

  @Test
  void missingTenant() {
    RoutingContext mockContext = mock(RoutingContext.class);
    when(mockContext.get("tenant")).thenReturn(null);
    Throwable t = Assertions.assertThrows(IllegalStateException.class, () -> {
      Tenant.get(mockContext);
    });
    assertEquals("X-Okapi-Tenant header is missing", t.getMessage());
  }

  @Test
  void missingPermissionsIncorrectSource() {
    RoutingContext mockContext = mock(RoutingContext.class);
    JsonArray perms = new JsonArray().add("reservoir-upload.source.other-source");
    when(mockContext.get("perms")).thenReturn(perms.encode()) ;
    Throwable t = Assertions.assertThrows(ForbiddenException.class, () -> {
      Tenant.ensurePermissionsSource(mockContext, "my-source");
    });
    assertEquals("Insufficient permissions to upload records for source 'my-source'", t.getMessage());
  }

  @Test
  void missingPermissionsNull() {
    RoutingContext mockContext = mock(RoutingContext.class);
    when(mockContext.get("perms")).thenReturn(null);
    Throwable t = Assertions.assertThrows(ForbiddenException.class, () -> {
      Tenant.ensurePermissionsSource(mockContext, "my-source");
    });
    assertEquals("Insufficient permissions to upload records for source 'my-source'", t.getMessage());
  }

  @Test
  void missingPermissionsEmpty() {
    RoutingContext mockContext = mock(RoutingContext.class);
    when(mockContext.get("perms")).thenReturn("");
    Throwable t = Assertions.assertThrows(ForbiddenException.class, () -> {
      Tenant.ensurePermissionsSource(mockContext, "my-source");
    });
    assertEquals("Insufficient permissions to upload records for source 'my-source'", t.getMessage());
  }

  @Test
  void missingPermissionsInvalid() {
    RoutingContext mockContext = mock(RoutingContext.class);
    when(mockContext.get("perms")).thenReturn("{");
    Throwable t = Assertions.assertThrows(ForbiddenException.class, () -> {
      Tenant.ensurePermissionsSource(mockContext, "my-source");
    });
    assertEquals("Cannot verify permissions to upload records for source 'my-source'", t.getMessage());
  }
}
