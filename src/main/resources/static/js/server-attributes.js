/*
 * The attributes this deployment keeps about a server, on the server's page.
 *
 * Which attributes exist is an administrator's decision, made on the administration page,
 * so this draws whatever it is handed: a box, a drop-down, a set of tick boxes, a switch.
 * Each attribute saves on its own, because that is what the endpoint takes and what the
 * audit record is about.
 */
(function (window, $) {
    'use strict';

    function control(attribute, editable) {
        var id = 'attribute-' + attribute.id;
        var values = attribute.values || [];
        var disabled = editable ? '' : ' disabled';

        if (attribute.type === 'BOOLEAN') {
            return '<label class="form-check form-switch mb-0">'
                + '<input class="form-check-input attribute-input" type="checkbox" id="' + id + '"'
                + (values.indexOf('true') >= 0 ? ' checked' : '') + disabled + '>'
                + '<span class="form-check-label">' + (values.indexOf('true') >= 0 ? 'Yes' : 'No') + '</span>'
                + '</label>';
        }
        if (attribute.type === 'CHOICE') {
            if (attribute.multiValued) {
                return attribute.options.map(function (option, index) {
                    return '<label class="form-check">'
                        + '<input class="form-check-input attribute-input" type="checkbox"'
                        + ' id="' + id + '-' + index + '" value="' + CertAlert.escapeHtml(option) + '"'
                        + (values.indexOf(option) >= 0 ? ' checked' : '') + disabled + '>'
                        + '<span class="form-check-label">' + CertAlert.escapeHtml(option) + '</span>'
                        + '</label>';
                }).join('');
            }
            return '<select class="form-select attribute-input" id="' + id + '"' + disabled + '>'
                + '<option value="">—</option>'
                + attribute.options.map(function (option) {
                    return '<option value="' + CertAlert.escapeHtml(option) + '"'
                        + (values.indexOf(option) >= 0 ? ' selected' : '') + '>'
                        + CertAlert.escapeHtml(option) + '</option>';
                }).join('')
                + '</select>';
        }
        if (attribute.multiValued) {
            // One value per line: a list somebody can paste into, rather than a widget.
            return '<textarea class="form-control attribute-input" id="' + id + '" rows="3"'
                + ' placeholder="one value per line"' + disabled + '>'
                + CertAlert.escapeHtml(values.join('\n')) + '</textarea>';
        }
        return '<input type="text" class="form-control attribute-input" id="' + id + '"'
            + ' value="' + CertAlert.escapeHtml(values.length ? values[0] : '') + '"' + disabled + '>';
    }

    /** What the control currently says, in the shape the endpoint takes. */
    function read($row, attribute) {
        if (attribute.type === 'BOOLEAN') {
            return $row.find('.attribute-input').is(':checked') ? ['true'] : [];
        }
        if (attribute.type === 'CHOICE' && attribute.multiValued) {
            return $row.find('.attribute-input:checked').map(function () { return this.value; }).get();
        }
        var value = $row.find('.attribute-input').val() || '';
        if (attribute.multiValued) {
            return value.split('\n').map(function (line) { return line.trim(); }).filter(Boolean);
        }
        return value.trim() ? [value.trim()] : [];
    }

    function row(attribute, editable) {
        return '<div class="row g-2 align-items-start attribute-row mb-3" data-attribute-id="' + attribute.id + '">'
            + '<div class="col-12 col-md-3">'
            + '<label class="form-label mb-0" for="attribute-' + attribute.id + '">'
            + CertAlert.escapeHtml(attribute.name) + '</label>'
            + (attribute.description
                ? '<div class="text-secondary small">' + CertAlert.escapeHtml(attribute.description) + '</div>'
                : '')
            + '</div>'
            + '<div class="col-12 col-md-6">' + control(attribute, editable) + '</div>'
            + '<div class="col-12 col-md-3 d-flex align-items-center gap-2">'
            + (editable ? '<button type="button" class="btn btn-sm attribute-save" disabled>Save</button>' : '')
            + '<span class="attribute-message text-secondary small"></span>'
            + '</div>'
            + '</div>';
    }

    function load($card) {
        var serverId = $card.data('server-id');
        $.getJSON('/api/v1/servers/' + serverId + '/attributes')
            .done(function (result) {
                if (!result.attributes.length) {
                    $card.addClass('d-none');
                    return;
                }
                $card.removeClass('d-none');
                var $list = $card.find('.attribute-list');
                $list.html(result.attributes.map(function (attribute) {
                    return row(attribute, result.editable);
                }).join(''));

                result.attributes.forEach(function (attribute) {
                    var $row = $list.find('[data-attribute-id="' + attribute.id + '"]');
                    var $save = $row.find('.attribute-save');
                    $row.on('input change', '.attribute-input', function () {
                        $save.prop('disabled', false);
                        $row.find('.attribute-message').text('').removeClass('text-danger');
                    });
                    $save.on('click', function () {
                        save($row, serverId, attribute);
                    });
                });
            })
            .fail(function (xhr) {
                if (CertAlert.handleUnauthorized(xhr)) {
                    return;
                }
                $card.removeClass('d-none').find('.attribute-list')
                    .html('<div class="text-secondary">Could not load the managed attributes.</div>');
            });
    }

    function save($row, serverId, attribute) {
        var $save = $row.find('.attribute-save').prop('disabled', true);
        var $message = $row.find('.attribute-message');

        $.ajax({
            url: '/api/v1/servers/' + serverId + '/attributes/' + attribute.id,
            type: 'PUT',
            contentType: 'application/json',
            data: JSON.stringify({values: read($row, attribute)})
        })
            .done(function (saved) {
                attribute.values = saved.values;
                $message.text('Saved').removeClass('text-danger');
                if (attribute.type === 'BOOLEAN') {
                    $row.find('.form-check-label').text(saved.values.indexOf('true') >= 0 ? 'Yes' : 'No');
                }
            })
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    CertAlert.handleUnauthorized(xhr);
                    return;
                }
                var detail = xhr.responseJSON && (xhr.responseJSON.detail || xhr.responseJSON.title);
                $message.text(xhr.status === 403
                        ? 'Only an administrator can change this.'
                        : (detail || 'Could not save.'))
                    .addClass('text-danger');
                $save.prop('disabled', false);
            });
    }

    $(function () {
        var $card = $('#server-attributes');
        if ($card.length) {
            load($card);
        }
    });
})(window, jQuery);
