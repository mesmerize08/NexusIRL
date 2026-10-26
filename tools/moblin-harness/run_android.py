"""Runs upstream Moblin's system-test suites against NexusIRL on Android.

Same test cases and Moblin driver as `python -m tests.test`, without the macOS-only startup (no
check for qrtool/ltc/lsof/tcpdump). NexusIRL's web remote control needs an access token: the
imported settings set a known test token and every request to the phone's web remote control
sends it as the login cookie. Usage:

    python run_android.py --device s26 [--suites stream,settings] [test-pattern]
"""
import argparse
import logging

import requests
import systest

from tests.suites import mic, record, scenes, settings, stream
from tests.utils import generate_device_settings, moblin as moblin_module
from tests.utils.config import Config
from tests.utils.runner import _remove_previous_run_artifacts
from tests.utils.moblin import Moblin

SUITES = {"stream": stream, "settings": settings, "scenes": scenes, "mic": mic, "record": record}
TEST_TOKEN = "00112233445566778899aabbccddeeff"

original_base_settings = generate_device_settings.base_settings


def base_settings(config, remote_control_port):
    settings = original_base_settings(config, remote_control_port)
    settings["remoteControl"]["web"]["nexusirlToken"] = TEST_TOKEN
    return settings


generate_device_settings.base_settings = base_settings
moblin_module.base_settings = base_settings

original_request = requests.Session.request


def request(self, method, url, *args, **kwargs):
    if ":1180" in str(url):
        kwargs.setdefault("cookies", {})["nexusirl_token"] = TEST_TOKEN
    return original_request(self, method, url, *args, **kwargs)


requests.Session.request = request

parser = argparse.ArgumentParser(description="Moblin system tests against NexusIRL.")
parser.add_argument("--device", required=True)
parser.add_argument("--suites", default="stream")
_remove_previous_run_artifacts("android")
sequencer = systest.setup("android", parser, add_date_to_log_filename=False)
sequencer.remove_filtered_testcases = True
sequencer.compact_output = True
args = parser.parse_args()
logging.getLogger("urllib3.connectionpool").setLevel(logging.INFO)
logging.getLogger("websockets").setLevel(logging.WARNING)
moblin = Moblin(Config(), args.device)
with moblin:
    moblin.end()
    sequencer.run(*[SUITES[name].tests(moblin) for name in args.suites.split(",")])
sequencer.report_and_exit(json=False, dot=False)
