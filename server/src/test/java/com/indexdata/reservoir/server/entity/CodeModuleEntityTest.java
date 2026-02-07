package com.indexdata.reservoir.server.entity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class CodeModuleEntityTest {
  @ParameterizedTest
  @CsvSource({
      "https://raw.githubusercontent.com/user/repo/branch/file.js, https://api.github.com/repos/user/repo/contents/file.js?ref=branch",
      "https://raw.githubusercontent.com/user/repo/branch/dir/file.js, https://api.github.com/repos/user/repo/contents/dir/file.js?ref=branch",
      "https://raw.githubusercontent.com/user/repo/branch/dir/subdir/file.js, https://api.github.com/repos/user/repo/contents/dir/subdir/file.js?ref=branch",
      "https://example.com/file.js, https://example.com/file.js",
      "https://raw.githubusercontent.com/x, https://raw.githubusercontent.com/x",
  })
  public void testTransformToApiFetch(String url, String expected) {
    String rawPrefix = "https://raw.githubusercontent.com/";
    String apiPrefix = "https://api.github.com/";
    String transformed = CodeModuleEntity.CodeModuleBuilder.transformToApiFetch(url, rawPrefix, apiPrefix);
    assertThat(transformed, is(expected));
  }
}
