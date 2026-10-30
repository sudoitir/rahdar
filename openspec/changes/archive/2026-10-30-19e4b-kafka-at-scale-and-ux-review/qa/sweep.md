# Screenshot sweep

Desktop only: 1440, 1920, 1280, and 200% zoom, light and dark, over the estate, registration, the Kafka screens and the shared screens on Kafka.

The first complete run, against the build before the layout fixes below, was 1,296 captures with 306 failures. The causes that repeated were:

- The registration form inserted the parsed-server row when the first server was typed, and at 200% zoom the action buttons wrapped onto a second line.
- A group's messages drew the queue toolbar, then replaced it with the partition controls when the cluster loaded, which pushed the grid.
- The header's firing count wrapped at 200% zoom and moved the page.
- A reader who cannot see a Kafka resource receives 404. The sweep counted that answer, and the browser's log of it, as a failure.
- The bulk-run capture asked for a run id that does not exist before the scene installed the answer.
- A few scenes clicked a control that a short window had not mounted yet (`Change retention.ms`, a row's Actions).
- One registration capture failed the 24px target-size check on the columns button of the connection-check table.

The product and the check were changed for the first five. A second run of the same 1,296 captures was stopped so this change could ship. Up to that point registration no longer failed layout shift. Groups at 200% zoom, the columns-button target size, and the topic-configuration clicks at 1280 and 200% zoom were still open. Those three are recorded here and are not new Kafka features.

# Flow review

See `review.md`. Hanging-transaction copy already separates a transaction past its timeout from one the coordinator does not know. Browse of `billing` on `orders` showed the offset the broker reported. No new Kafka feature came out of the review.
