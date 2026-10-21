/*
 * The idle timeout, said before it happens (WCAG 2.2.1, timing adjustable).
 *
 * The session ends after a set time with no request to the server (ENS_SESSION_IDLE_TIMEOUT;
 * administrators' may be shorter). Two minutes before that, an alert says so and offers to
 * stay signed in - which is one request, and the clock starts again. Every request the page
 * makes restarts it too. Once it has passed, the alert says the session has ended.
 */
(function (window, $) {
    'use strict';

    var meta = document.querySelector('meta[name="cert-alert-idle-timeout"]');
    if (!meta) {
        return;
    }
    var timeout = parseInt(meta.getAttribute('content'), 10) * 1000;
    if (!(timeout > 0)) {
        return;
    }
    var warnBefore = Math.min(120000, timeout / 2);
    var warnTimer;
    var endTimer;
    var box;

    function message(html, focus) {
        if (!box) {
            box = document.createElement('div');
            box.className = 'alert alert-warning shadow session-timeout';
            box.setAttribute('role', 'alertdialog');
            box.setAttribute('aria-live', 'assertive');
            box.setAttribute('aria-labelledby', 'session-timeout-text');
            document.body.appendChild(box);
        }
        box.innerHTML = html;
        box.hidden = false;
        if (focus) {
            var target = box.querySelector('button, a');
            if (target) {
                target.focus();
            }
        }
    }

    function hide() {
        if (box) {
            box.hidden = true;
        }
    }

    function restart() {
        window.clearTimeout(warnTimer);
        window.clearTimeout(endTimer);
        hide();
        warnTimer = window.setTimeout(function () {
            var minutes = Math.round(warnBefore / 60000);
            message('<p id="session-timeout-text" class="mb-2">Your session ends in '
                + (minutes >= 1 ? minutes + ' minute' + (minutes === 1 ? '' : 's') : 'under a minute')
                + ' unless something is done here.</p>'
                + '<button type="button" class="btn btn-primary btn-sm" id="session-timeout-stay">Stay signed in</button>', true);
        }, timeout - warnBefore);
        endTimer = window.setTimeout(function () {
            message('<p id="session-timeout-text" class="mb-2">Your session has ended.</p>'
                + '<a class="btn btn-primary btn-sm" href="/login">Sign in again</a>', true);
        }, timeout);
    }

    $(document).on('click', '#session-timeout-stay', function () {
        // Any request will do; this one is cheap and changes nothing.
        $.get('/api/v1/notifications/unread-count');
    });
    // Every request the page makes counts as activity on the server, so it does here - the
    // tables' own requests (DataTables makes them without jQuery) as much as anything else.
    var send = window.XMLHttpRequest.prototype.send;
    window.XMLHttpRequest.prototype.send = function () {
        this.addEventListener('loadend', restart);
        return send.apply(this, arguments);
    };
    if (window.fetch) {
        var fetch = window.fetch;
        window.fetch = function () {
            return fetch.apply(this, arguments).finally(restart);
        };
    }
    restart();
})(window, jQuery);
