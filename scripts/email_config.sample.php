<?php
// Where to send a notification whenever a recipe fails to convert
// cleanly -- either the script couldn't find a usable recipe at all, or
// (a more concerning case, since it suggests an actual bug rather than
// "this site just isn't supported") it reported success but produced
// output that couldn't even be parsed as JSON. Set to '' to disable
// notifications entirely.
const NOTIFY_EMAIL = 'you@example.com';
