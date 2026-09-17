/*
 * The audit table on a details page.
 *
 * The same search table as the other two - DataTables' paging, ordering and search, decided
 * server side - narrowed to the one entry the page is about. The history block in an
 * expanded row is the quick look; this is the one to search.
 */
(function (window, $) {
    'use strict';

    /** Tabler's light-tint badges, by what kind of thing happened. */
    var ACTION_BADGE = {
        ENTRY_DISCOVERED: 'bg-secondary-lt',
        ENTRY_PRUNED: 'bg-red-lt',
        CERTIFICATE_CACHED: 'bg-green-lt',
        CERTIFICATE_REMOVED: 'bg-orange-lt',
        CERTIFICATE_STATUS_CHANGED: 'bg-yellow-lt',
        CONTACT_ADDED: 'bg-blue-lt',
        CONTACT_REMOVED: 'bg-azure-lt',
        ALERT_SENT: 'bg-purple-lt',
        ALERT_FAILED: 'bg-red-lt'
    };

    /** A moment, to the minute: these are events, and the second is rarely the point. */
    function moment(iso) {
        if (!iso) {
            return '';
        }
        var parsed = new Date(iso);
        return isNaN(parsed.getTime()) ? '' : parsed.toISOString().slice(0, 16).replace('T', ' ') + 'Z';
    }

    $(function () {
        var element = document.getElementById('audit-table');
        if (!element) {
            return;
        }
        var subjectType = element.dataset.subjectType;
        var subjectId = element.dataset.subjectId;
        var kind = document.getElementById('audit-kind');

        function readFilters() {
            var filters = {subjectType: subjectType, subjectId: subjectId};
            if (kind && kind.value) {
                filters.action = kind.value;
            }
            return filters;
        }

        CertAlert.initTable({
            selector: '#audit-table',
            endpoint: '/api/v1/datatables/audit',
            readFilters: readFilters,
            filterInputs: [kind],
            // Newest first: the recent end is the one being looked at.
            order: [[0, 'desc']],
            emptyMessage: 'Nothing has happened to this entry yet',
            columns: [
                {data: 'occurredAt', name: 'occurredAt', searchable: false, className: 'mono audit-when',
                    render: function (value, type) {
                        return type === 'display' ? CertAlert.escapeHtml(moment(value)) : value;
                    }},
                // Ordered and filtered by the action itself; the label is what is shown.
                {data: 'label', name: 'action', searchable: false, className: 'audit-action',
                    render: function (value, type, row) {
                        if (type !== 'display') { return value; }
                        return '<span class="badge ' + (ACTION_BADGE[row.action] || 'bg-secondary-lt') + '">'
                            + CertAlert.escapeHtml(value) + '</span>';
                    }},
                {data: 'summary', name: 'summary', render: function (value, type) {
                    return type === 'display' ? CertAlert.text(value) : value;
                }},
                {data: 'actor', name: 'actor', className: 'audit-actor', render: function (value, type) {
                    return type === 'display' ? CertAlert.text(value) : value;
                }},
                {data: 'channel', name: 'channel', render: function (value, type) {
                    return type === 'display' ? CertAlert.text(value) : value;
                }},
                {data: 'target', name: 'target', render: function (value, type) {
                    return type === 'display' ? CertAlert.text(value) : value;
                }},
                // Searchable but not shown: it is how one certificate is followed through
                // the trail, and it is sixty-four characters of hex.
                {data: 'certificateFingerprint', name: 'certificateFingerprint', visible: false}
            ]
        });
    });
})(window, jQuery);
