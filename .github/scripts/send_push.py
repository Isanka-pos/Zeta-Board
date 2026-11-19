"""Sends the "new version" push notification to every Zeta Board install (FCM topic "updates").

Runs in GitHub Actions right after the release is published. Needs the repo secret FIREBASE_SERVICE_ACCOUNT
(the full JSON of a Firebase service account key). Without it the script just skips, so builds never break.
Use --dry-run to print the message without sending it.
"""
import json
import os
import sys
import urllib.error
import urllib.request

TOPIC = "updates"
MAX_NOTES_BYTES = 2500  # FCM data messages are limited to 4 KB in total


def short(text, limit):
    raw = text.encode("utf-8")
    if len(raw) <= limit:
        return text
    return raw[:limit].decode("utf-8", errors="ignore").rstrip() + "\u2026"


def build_message(update):
    return {
        "message": {
            "topic": TOPIC,
            "data": {
                "type": "update",
                "versionCode": str(update["versionCode"]),
                "versionName": str(update.get("versionName", "")),
                "apkUrl": update["apkUrl"],
                "notes": short(update.get("notes", ""), MAX_NOTES_BYTES),
            },
            # data-only + high priority: the app builds the notification itself, even when it is in the background
            "android": {"priority": "HIGH", "ttl": "86400s"},
        }
    }


def main():
    with open("update.json", encoding="utf-8") as f:
        body = build_message(json.load(f))

    if "--dry-run" in sys.argv:
        print(json.dumps(body, ensure_ascii=False, indent=2))
        return 0

    raw = os.environ.get("FIREBASE_SERVICE_ACCOUNT", "").strip()
    if not raw:
        print("FIREBASE_SERVICE_ACCOUNT secret is not set - skipping push notification.")
        return 0
    account = json.loads(raw)

    from google.auth.transport.requests import Request
    from google.oauth2 import service_account

    creds = service_account.Credentials.from_service_account_info(
        account, scopes=["https://www.googleapis.com/auth/firebase.messaging"]
    )
    creds.refresh(Request())

    url = "https://fcm.googleapis.com/v1/projects/%s/messages:send" % account["project_id"]
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers={"Authorization": "Bearer " + creds.token, "Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            print("Push sent:", r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        print("Push failed: HTTP", e.code, e.read().decode("utf-8"))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
