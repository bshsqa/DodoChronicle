# ENH-009: Timeline Photo Browse and Hidden Event UX

## Scope

This enhancement covers two timeline UX areas:

- Browse all device photos taken on a selected date from the day detail dialog.
- Hide text events and restore hidden text events with a safer multi-select flow.

Out of scope:

- Adding photos from an individual date card. Photo import/add remains a global timeline action and uses the photo's own `takenAt` date.

## Photo Browse UX

### Requirements

- The collapsed date-card list must not show a `사진+` action.
- A `사진+` action is shown only inside the expanded day detail dialog, near the date title.
- Tapping `사진+` shows all device photos from that calendar date, queried from MediaStore by `DATE_TAKEN`.
- The photo list includes photos that are not yet registered in DodoChronicle.
- Tapping a photo opens the fullscreen photo viewer.

### Notes

- DodoChronicle event photos remain the source for the timeline preview thumbnails.
- The `사진+` dialog is a device gallery view for that date, not a registered-event-only view.

## Hidden Event UX

### Requirements

- Text events can be hidden from the day detail dialog.
- If hiding the selected text event leaves no visible events in the day detail dialog, the day detail dialog closes immediately.
- If visible events remain, the day detail dialog stays open.
- The settings `숨김 아이템` dialog does not restore on long press.
- Hidden events can be selected one or more at a time.
- A bottom `복구` button restores the selected hidden events in one batch.
- If restoring selected hidden events leaves no hidden events, the hidden items dialog closes immediately.
- If hidden events remain, the dialog stays open.

## Data and Model Notes

- `events.isHidden` stores text-event soft deletion.
- Normal timeline queries exclude hidden events.
- The hidden-events query returns hidden events for restoration.
- Photo learning include/exclude uses the real `photo_records.id`; UI must not synthesize fake `PhotoRecord` IDs from event IDs.
- Child embedding refresh uses the latest 50 non-excluded photo embeddings for the current child.
- If no non-excluded photo embeddings remain, the child embedding list is cleared to avoid stale vectors.

## Acceptance Criteria

- Collapsed date cards have no `사진+` button.
- Expanded day detail dialog has a date-level `사진+` button.
- Date-level `사진+` shows all MediaStore photos from that date, including unregistered photos.
- Hiding the only visible event in a day detail dialog closes that dialog.
- Hiding one event while others remain keeps the day detail dialog open.
- Hidden items restore via multi-select plus `복구`, not long press.
- Restoring all hidden items closes the hidden items dialog.
- Restoring only some hidden items keeps the dialog open.
