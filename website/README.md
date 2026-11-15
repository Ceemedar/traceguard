# Private dashboard module

Planned stack: React, TypeScript, Leaflet, OpenStreetMap tiles, Firebase Authentication, and Cloud Firestore.

Planned pages: login, device dashboard, location history, and device/account settings. Pages must show the time of the last report and clearly mark stale data. A live Firestore listener indicates that server data changed; it does not mean the phone is continuously transmitting.

Run `npm install` and `npm run dev` from this directory. Copy the root `.env.example` to `.env.local` and fill it with the Firebase web app values from project settings. The local file is ignored by Git.
