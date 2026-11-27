import 'dart:convert';

import 'package:anx_reader/enums/sync_protocol.dart';

// Configuration backups carry public settings only. Opaque headers, credentials
// and provider extensions remain on their installation, including when reading
// backups written before this policy existed.
const _providerFields = {
  'id',
  'title',
  'logoAsset',
  'url',
  'protocol',
  'enabled',
  'isBuiltin',
  'model',
  'authMode',
  'deployment',
  'reasoningEffort',
  'requestTimeoutSeconds',
  'createdAt',
  'updatedAt',
};
const _capabilityFields = {
  'supportsStreaming',
  'supportsJson',
  'supportsTools',
  'supportsVision',
  'supportsThinking',
  'maxContextTokens',
  'maxOutputTokens',
};
const _legacyAiFields = {
  'url',
  'model',
  'temperature',
  'top_p',
  'max_tokens',
  'max_output_tokens',
  'reasoning_effort',
  'request_timeout_seconds',
  'deployment',
};
const _ttsFields = {'url', 'region', 'model', 'voice', 'instructions'};
const _translationFields = {'api_url', 'region'};
const _searchFields = {
  'provider',
  'enabled',
  'endpoint',
  'queryParameter',
  'maxResults',
  'timeoutMs',
};
const _sourceFields = {'id', 'mode', 'domains'};
const _endpointFields = {'url', 'api_url', 'endpoint'};
const _targetFields = {
  'url',
  'api_url',
  'endpoint',
  'region',
  'provider',
  'protocol',
};

final _syncConfigKeys = {
  for (final protocol in SyncProtocol.values) '${protocol.name}Info',
};

/// Filters known configuration containers in both directions. [localValue] is
/// supplied only during restore: local credentials survive only when the
/// service target is unchanged, and never come from the backup being restored.
/// Null means an invalid config should be skipped, not copied verbatim.
Object? filterPrefsBackupConfig(
  String key,
  Object? value, {
  Object? localValue,
}) {
  final Set<String>? fields;
  if (key == 'aiProviders') {
    fields = _providerFields;
  } else if (_syncConfigKeys.contains(key)) {
    fields = const {'url'};
  } else if (key.startsWith('aiConfig_')) {
    fields = _legacyAiFields;
  } else if (key.startsWith('onlineTtsConfig_')) {
    fields = _ttsFields;
  } else if (key.startsWith('translateServiceConfig_')) {
    fields = _translationFields;
  } else if (key == 'readingWebSearchConfig') {
    fields = _searchFields;
  } else {
    return value;
  }
  if (value is! String) return null;
  try {
    final decoded = jsonDecode(value);
    Object? local;
    if (localValue is String) {
      try {
        local = jsonDecode(localValue);
      } on FormatException {
        // A malformed local config must not block a valid backup restore.
      }
    }
    if (key == 'aiProviders') {
      if (decoded is! List || decoded.any((item) => item is! Map)) return null;
      final providers = <Map>[];
      for (final provider in decoded.cast<Map>()) {
        Map? previous;
        if (local is List && provider['id'] is String) {
          for (final candidate in local.whereType<Map>()) {
            if (candidate['id'] == provider['id']) {
              previous = candidate;
              break;
            }
          }
        }
        providers.add(_filterConfig(provider, fields, previous));
      }
      return jsonEncode(providers);
    }
    if (decoded is! Map) return null;
    return jsonEncode(
      _filterConfig(decoded, fields, local is Map ? local : null),
    );
  } on FormatException {
    return null;
  }
}

Map<String, dynamic> _filterConfig(Map input, Set<String> fields, Map? local) {
  final safe = <String, dynamic>{};
  for (final field in fields) {
    final value = input[field];
    if (value is! String && value is! num && value is! bool) continue;
    safe[field] = _endpointFields.contains(field) && value is String
        ? _publicEndpoint(value)
        : value;
  }
  if (identical(fields, _providerFields) && input['capabilities'] is Map) {
    safe['capabilities'] = _filterConfig(
      input['capabilities'],
      _capabilityFields,
      null,
    );
  }
  if (identical(fields, _searchFields) && input['trustedSources'] is Map) {
    safe['trustedSources'] = _filterConfig(
      input['trustedSources'],
      _sourceFields,
      null,
    );
  }
  if (identical(fields, _sourceFields)) {
    final domains = input['domains'];
    if (domains is List && domains.every((domain) => domain is String)) {
      safe['domains'] = domains;
    }
  }
  // Compare the original local target with the sanitized incoming target. A
  // changed host, protocol, region or credential-bearing URL requires reauth.
  if (local != null &&
      _targetFields.every((field) => local[field] == safe[field])) {
    return {...Map<String, dynamic>.from(local), ...safe};
  }
  return safe;
}

String _publicEndpoint(String value) {
  final uri = Uri.tryParse(value);
  if (uri == null) return '';
  // Signed query strings, URI user-info and fragments can all carry secrets.
  return Uri(
    scheme: uri.scheme,
    host: uri.hasAuthority ? uri.host : null,
    port: uri.hasPort ? uri.port : null,
    path: uri.path,
  ).toString();
}
