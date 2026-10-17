// The contacts editor is written for the expanded row of the servers table; on the server's
// own page the same block is in a card, and it is attached the same way. A file rather than an
// inline block, because the Content-Security-Policy allows scripts from this application only.
jQuery(function ($) { ServerContacts.attach($(document)); });
