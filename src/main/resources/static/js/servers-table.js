/* The servers search table. */
(function ($) {
    'use strict';

    $(function () {
        var certState = document.getElementById('cert-state');
        var withinDays = document.getElementById('within-days');
        var pocEmail = document.getElementById('poc-email');
        var applied = document.getElementById('applied-filters');

        // Arriving from a user row pre-fills the contact filter, so the link from the
        // users table lands on an already-filtered server list.
        var initialPoc = new URLSearchParams(window.location.search).get('pocEmail');
        if (initialPoc) {
            pocEmail.value = initialPoc;
        }

        function readFilters() {
            var filters = {};
            switch (certState.value) {
                case 'expired':
                    filters.expired = 'true';
                    break;
                case 'not-expired':
                    filters.expired = 'false';
                    break;
                case 'expiring-soon':
                    filters.certificateStatus = 'EXPIRING_SOON';
                    break;
                case 'valid':
                    filters.certificateStatus = 'VALID';
                    break;
                case 'none':
                    filters.hasCertificates = 'false';
                    break;
                default:
                    break;
            }
            var days = withinDays.value.trim();
            if (days) {
                filters.expiringWithinDays = days;
            }
            var contact = pocEmail.value.trim();
            if (contact) {
                filters.pocEmail = contact;
            }
            return filters;
        }

        CertAlert.initTable({
            selector: '#servers-table',
            endpoint: '/api/v1/datatables/servers',
            readFilters: readFilters,
            filterInputs: [certState, withinDays, pocEmail],
            // Sort by name, not by expiry: entries with no certificate have a null
            // expiry, and databases disagree about whether nulls sort first or last.
            // The expiry-focused views are a column click or a filter away.
            order: [[1, 'asc']],
            detailUrl: function (row) {
                return '/api/v1/servers/' + row.id + '/certificates';
            },
            onFiltersApplied: function (filters) {
                var keys = Object.keys(filters);
                applied.innerHTML = keys.length === 0
                    ? 'No extra filters'
                    : 'Filtered by <strong>' + keys.map(function (key) {
                        return CertAlert.escapeHtml(key + '=' + filters[key]);
                    }).join('</strong>, <strong>') + '</strong>';
            },
            columns: [
                {data: 'id', orderable: false, searchable: false, className: 'expand',
                    render: function () { return '+'; }},
                {data: 'commonName', render: renderText},
                {data: 'fqdn', className: 'mono', render: renderText},
                {data: 'serverPocDisplay', render: renderText},
                {data: 'operatingSystem', render: renderText},
                {data: 'organization', render: renderText},
                {data: 'organizationalUnit', render: renderText},
                {data: 'certificateCount', searchable: false, className: 'mono'},
                {data: 'certificateStatus', searchable: false, render: function (value, type) {
                    return type === 'display' ? CertAlert.statusBadge(value) : value;
                }},
                {data: 'earliestExpiry', searchable: false, render: function (value, type) {
                    return type === 'display' ? CertAlert.expiryCell(value) : value;
                }},
                {data: 'lastSyncedAt', searchable: false, render: function (value, type) {
                    return type === 'display' ? CertAlert.text(CertAlert.formatDate(value)) : value;
                }},
                {data: 'dn', className: 'mono', render: renderText}
            ]
        });

        function renderText(value, type) {
            return type === 'display' ? CertAlert.text(value) : value;
        }
    });
})(jQuery);
