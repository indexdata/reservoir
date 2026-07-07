#!/usr/bin/env python3

"""
Do FOLIO login to get a once-off token.
i.e. not using the full refresh token regime.

Attempt login via new /authn/login-with-expiry endpoint.
If that fails (i.e. no such endpoint)
then attempt login via old /authn/login endpoint.

Required:
    Environment variables: OKAPI_URL, OKAPI_TENANT, OKAPI_USER, OKAPI_PW
Returns:
    token
"""

import argparse
import json
import logging
import os
import re
import sys
import urllib.error
import urllib.request

SCRIPT_VERSION = "1.0.0"

# pylint: disable=R0912
# pylint: disable=R0915

LOGLEVELS = {
    "debug": logging.DEBUG,
    "info": logging.INFO,
    "warning": logging.WARNING,
    "error": logging.ERROR,
    "critical": logging.CRITICAL,
}
PROG_NAME = os.path.basename(sys.argv[0])
PROG_PATH = os.path.dirname(os.path.abspath(__file__))
PROG_DESC = __import__("__main__").__doc__
LOG_FORMAT = "%(levelname)s: %(name)s: %(message)s"
LOGGER = logging.getLogger(PROG_NAME)


def get_options():
    """
    Gets the command-line options.
    Verifies configuration.
    """
    options_okay = True
    parser = argparse.ArgumentParser(description=PROG_DESC)
    parser.add_argument(
        "-l",
        "--loglevel",
        choices=["debug", "info", "warning", "error", "critical"],
        help="Logging level. (Default: %(default)s)",
    )
    args = parser.parse_args()
    logging.basicConfig(format=LOG_FORMAT)
    if args.loglevel:
        loglevel = LOGLEVELS.get(args.loglevel.lower(), logging.NOTSET)
        LOGGER.setLevel(loglevel)
    try:
        okapi_url = os.environ["OKAPI_URL"].rstrip("/")
    except KeyError:
        options_okay = False
        LOGGER.critical("Missing environment variable 'OKAPI_URL'.")
    try:
        okapi_tenant = os.environ["OKAPI_TENANT"]
    except KeyError:
        options_okay = False
        LOGGER.critical("Missing environment variable 'OKAPI_TENANT'.")
    try:
        okapi_user = os.environ["OKAPI_USER"]
    except KeyError:
        options_okay = False
        LOGGER.critical("Missing environment variable 'OKAPI_USER'.")
    try:
        okapi_pw = os.environ["OKAPI_PW"]
    except KeyError:
        options_okay = False
        LOGGER.critical("Missing environment variable 'OKAPI_PW'.")
    if not options_okay:
        sys.exit(2)
    return okapi_url, okapi_tenant, okapi_user, okapi_pw


def do_login(okapi_url, tenant, user, password):
    """
    Does Okapi login.
    Returns token.

    Attempt to login via new endpoint,
    if not found then use old endpoint.
    """
    headers = {
        "x-okapi-tenant": tenant,
        "content-type": "application/json",
        "accept": "application/json",
    }
    payload = {
        "username": user,
        "password": password,
    }
    payload_json = json.dumps(payload).encode("utf-8")
    style = "new-style"
    url_login = f"{okapi_url}/authn/login-with-expiry"
    LOGGER.debug("Login %s: Trying %s", style, url_login)
    req = urllib.request.Request(url_login, payload_json, headers)
    try:
        response = urllib.request.urlopen(req)
    except urllib.error.HTTPError as error:
        if error.status != 404:
            LOGGER.critical(
                "Login %s: HTTPError: %s %s", style, error.status, error.reason
            )
            sys.exit(1)
        else:
            LOGGER.debug("New RTR endpoint not found. Try old-style.")
    except urllib.error.URLError as error:
        LOGGER.critical("Login %s: URLError: %s", style, error.reason)
        sys.exit(1)
    else:
        token = None
        cookie_headers = response.headers.get_all("Set-Cookie")
        cookie_re = re.compile(r"^folioAccessToken=([^;]+);")
        for cookie_header in cookie_headers:
            match = re.search(cookie_re, cookie_header)
            if match:
                token = match.group(1)
                break
        if not token:
            LOGGER.critical("Login %s: Could not get Okapi token.", style)
            sys.exit(1)
        return token
    style = "old-style"
    url_login = f"{okapi_url}/authn/login"
    LOGGER.debug("Login %s: Trying %s", style, url_login)
    req = urllib.request.Request(url_login, payload_json, headers)
    try:
        response = urllib.request.urlopen(req)
    except urllib.error.HTTPError as error:
        LOGGER.critical("Login %s: HTTPError: %s %s", style, error.status, error.reason)
        sys.exit(1)
    except urllib.error.URLError as error:
        LOGGER.critical("Login %s: URLError: %s", style, error.reason)
        sys.exit(1)
    else:
        token = response.headers.get("x-okapi-token")
        if not token:
            LOGGER.critical("Login %s: Could not get Okapi token.", style)
            sys.exit(1)
    return token


def main():
    """
    Do FOLIO login to get a once-off token.
    i.e. not using the full refresh token regime.

    Returns:
        token
    Exit values:
        0: Success.
        1: One or more failures with processing.
        2: Configuration issues.
    """
    exit_code = 0
    (okapi_url, okapi_tenant, okapi_user, okapi_pw) = get_options()
    token = do_login(okapi_url, okapi_tenant, okapi_user, okapi_pw)
    print(token)
    logging.shutdown()
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
