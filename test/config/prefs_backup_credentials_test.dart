import 'dart:convert';
import 'dart:io';

import 'package:anx_reader/config/shared_preference_provider.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

Future<Prefs> _initialize(Map<String, Object> values) async {
  SharedPreferences.setMockInitialValues(values);
  final prefs = Prefs();
  await prefs.initPrefs();
  return prefs;
}

Map<String, dynamic> _backup(Map<String, Object?> configs) => {
  for (final entry in configs.entries)
    entry.key: {'type': 'string', 'value': jsonEncode(entry.value)},
};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final configs = <String, Object?>{
    'webdavInfo': {
      'url': 'https://sync.example.test/dav',
      'username': 'private-user',
      'password': 'private-password',
    },
    'aiProviders': [
      {
        'id': 'custom-provider',
        'title': 'My provider',
        'url': 'https://ai.example.test/v1',
        'protocol': 'openai',
        'model': 'custom-model',
        'apiKeys': [
          {'id': 'key-1', 'key': 'private-ai-key', 'enabled': true},
        ],
        'headers': {'Authorization': 'private-header'},
        'capabilities': {'supportsTools': true, 'apiKey': 'private-nested-key'},
      },
    ],
    'aiConfig_custom-provider': {
      'url': 'https://ai.example.test/v1',
      'model': 'custom-model',
      'temperature': '0.5',
      'api_key': 'private-legacy-key',
      'headers': '{"Authorization":"private-header"}',
      'extra': '{"api_key":"private-extension"}',
    },
    'onlineTtsConfig_openai': {
      'url': 'https://tts.example.test/v1',
      'voice': 'alloy',
      'model': 'tts-1',
      'instructions': 'Read slowly',
      'key': 'private-tts-key',
    },
    'onlineTtsConfig_azure': {'region': 'eastus', 'key': 'private-azure-key'},
    'onlineTtsConfig_aliyun': {
      'voice': 'xiaoyun',
      'appkey': 'private-appkey',
      'accessKeyId': 'private-access-id',
      'accessKeySecret': 'private-access-secret',
    },
    'translateServiceConfig_deepl': {
      'api_url': 'https://translation.example.test/v2',
      'api_key': 'private-translation-key',
    },
    'translateServiceConfig_microsoftApi': {
      'region': 'eastus',
      'api_key': 'private-microsoft-key',
    },
    'readingWebSearchConfig': {
      'provider': 'custom',
      'enabled': true,
      'endpoint': 'https://search.example.test/',
      'maxResults': 7,
      'apiKey': 'private-search-key',
      'headers': {'X-Custom-Auth': 'private-header'},
      'trustedSources': {
        'id': 'custom-sources',
        'mode': 'general',
        'domains': ['example.test'],
        'token': 'private-nested-token',
      },
    },
  };
  final publicConfigs = <String, Object?>{
    'webdavInfo': {'url': 'https://sync.example.test/dav'},
    'aiProviders': [
      {
        'id': 'custom-provider',
        'title': 'My provider',
        'url': 'https://ai.example.test/v1',
        'protocol': 'openai',
        'model': 'custom-model',
        'capabilities': {'supportsTools': true},
      },
    ],
    'aiConfig_custom-provider': {
      'url': 'https://ai.example.test/v1',
      'model': 'custom-model',
      'temperature': '0.5',
    },
    'onlineTtsConfig_openai': {
      'url': 'https://tts.example.test/v1',
      'voice': 'alloy',
      'model': 'tts-1',
      'instructions': 'Read slowly',
    },
    'onlineTtsConfig_azure': {'region': 'eastus'},
    'onlineTtsConfig_aliyun': {'voice': 'xiaoyun'},
    'translateServiceConfig_deepl': {
      'api_url': 'https://translation.example.test/v2',
    },
    'translateServiceConfig_microsoftApi': {'region': 'eastus'},
    'readingWebSearchConfig': {
      'provider': 'custom',
      'enabled': true,
      'endpoint': 'https://search.example.test/',
      'maxResults': 7,
      'trustedSources': {
        'id': 'custom-sources',
        'mode': 'general',
        'domains': ['example.test'],
      },
    },
  };

  test('service configs export public settings without credentials', () async {
    final prefs = await _initialize({
      for (final entry in configs.entries) entry.key: jsonEncode(entry.value),
    });
    final backup = await prefs.buildPrefsBackupMap();
    expect(jsonEncode(backup), isNot(contains('private-')));
    for (final entry in publicConfigs.entries) {
      expect(
        jsonDecode(backup[entry.key]['value']),
        entry.value,
        reason: entry.key,
      );
      // Building a backup must not mutate the installation's credentials.
      expect(jsonDecode(prefs.prefs.getString(entry.key)!), configs[entry.key]);
    }
  });

  test(
    'legacy backups cannot install nested credentials on a fresh device',
    () async {
      final prefs = await _initialize({});
      await prefs.applyPrefsBackupMap(_backup(configs));
      for (final entry in publicConfigs.entries) {
        expect(
          jsonDecode(prefs.prefs.getString(entry.key)!),
          entry.value,
          reason: entry.key,
        );
      }
      expect(
        jsonEncode(await prefs.buildPrefsBackupMap()),
        isNot(contains('private-')),
      );
    },
  );

  test(
    'restore keeps local credentials only for unchanged service targets',
    () async {
      final prefs = await _initialize({
        for (final entry in configs.entries) entry.key: jsonEncode(entry.value),
      });
      final legacy = jsonDecode(jsonEncode(configs)) as Map<String, dynamic>;
      legacy['webdavInfo']['password'] = 'foreign-password';
      legacy['aiProviders'][0]['apiKeys'][0]['key'] = 'foreign-ai-key';
      legacy['aiProviders'][0]['model'] = 'restored-model';
      legacy['readingWebSearchConfig']['apiKey'] = 'foreign-search-key';
      await prefs.applyPrefsBackupMap(_backup(legacy));
      expect(
        jsonDecode(prefs.prefs.getString('webdavInfo')!)['password'],
        'private-password',
      );
      final provider =
          (jsonDecode(prefs.prefs.getString('aiProviders')!) as List).single;
      expect(provider['apiKeys'][0]['key'], 'private-ai-key');
      expect(provider['model'], 'restored-model');
      expect(
        jsonDecode(prefs.prefs.getString('readingWebSearchConfig')!)['apiKey'],
        'private-search-key',
      );
      expect(
        jsonEncode(await prefs.buildPrefsBackupMap()),
        isNot(contains('private-')),
      );

      legacy['webdavInfo']['url'] = 'https://different.example.test/dav';
      legacy['aiProviders'][0]['url'] = 'https://different.example.test/ai';
      legacy['aiConfig_custom-provider']['url'] =
          'https://different.example.test/ai';
      legacy['onlineTtsConfig_openai']['url'] =
          'https://different.example.test/tts';
      legacy['onlineTtsConfig_azure']['region'] = 'westus';
      legacy['onlineTtsConfig_aliyun']['url'] =
          'https://different.example.test/tts';
      legacy['translateServiceConfig_deepl']['api_url'] =
          'https://different.example.test/translate';
      legacy['translateServiceConfig_microsoftApi']['region'] = 'westus';
      legacy['readingWebSearchConfig']['endpoint'] =
          'https://different.example.test/search';
      await prefs.applyPrefsBackupMap(_backup(legacy));
      for (final key in configs.keys) {
        expect(
          prefs.prefs.getString(key),
          isNot(contains('private-')),
          reason: key,
        );
        expect(
          prefs.prefs.getString(key),
          isNot(contains('foreign-')),
          reason: key,
        );
      }
    },
  );

  test(
    'credentials embedded in endpoint URLs never leave the installation',
    () async {
      final endpoints = {
        'webdavInfo': 'url',
        'aiConfig_custom': 'url',
        'onlineTtsConfig_custom': 'url',
        'translateServiceConfig_deepl': 'api_url',
        'readingWebSearchConfig': 'endpoint',
      };
      final unsafe =
          'https://private-user:private-password@api.example.test:8443/v1'
          '?token=private-query#private-fragment';
      final prefs = await _initialize({
        for (final entry in endpoints.entries)
          entry.key: jsonEncode({entry.value: unsafe}),
      });
      final backup = await prefs.buildPrefsBackupMap();
      for (final entry in endpoints.entries) {
        expect(
          jsonDecode(backup[entry.key]['value'])[entry.value],
          'https://api.example.test:8443/v1',
        );
      }
      expect(jsonEncode(backup), isNot(contains('private-')));
      await _initialize({});
      await prefs.applyPrefsBackupMap(
        _backup({
          for (final entry in endpoints.entries)
            entry.key: {entry.value: unsafe},
        }),
      );
      for (final entry in endpoints.entries) {
        expect(
          jsonDecode(prefs.prefs.getString(entry.key)!)[entry.value],
          'https://api.example.test:8443/v1',
        );
      }
    },
  );

  test(
    'malformed configuration entries are skipped without losing local state',
    () async {
      final prefs = await _initialize({
        'webdavInfo': jsonEncode(configs['webdavInfo']),
        'aiProviders': jsonEncode(configs['aiProviders']),
        'aiConfig_custom-provider': jsonEncode(
          configs['aiConfig_custom-provider'],
        ),
        'autoSync': false,
      });
      await prefs.applyPrefsBackupMap({
        'webdavInfo': {'type': 'string', 'value': '{invalid-json'},
        'aiProviders': {'type': 'string', 'value': '[42]'},
        'aiConfig_custom-provider': {
          'type': 'stringList',
          'value': ['private-key'],
        },
        'autoSync': {'type': 'bool', 'value': true},
      });
      for (final key in [
        'webdavInfo',
        'aiProviders',
        'aiConfig_custom-provider',
      ]) {
        expect(jsonDecode(prefs.prefs.getString(key)!), configs[key]);
      }
      expect(prefs.autoSync, isTrue);
      await prefs.prefs.setString(
        'onlineTtsConfig_custom',
        '{invalid-local-json',
      );
      await prefs.applyPrefsBackupMap(
        _backup({
          'onlineTtsConfig_custom': {'voice': 'restored', 'key': 'foreign-key'},
        }),
      );
      expect(jsonDecode(prefs.prefs.getString('onlineTtsConfig_custom')!), {
        'voice': 'restored',
      });
    },
  );

  test('all current preference accessors have an explicit backup policy', () async {
    // Discover actual storage keys rather than copying the supported registry.
    // Adding a new accessor without choosing backup/local policy must fail.
    final values = <String, Object>{};
    final access = RegExp(
      r"""(?:\b(?:prefs|_prefs)(?:\.prefs)?|Prefs\(\)\.prefs)\.(?:get|set)(Bool|Int|Double|StringList|String)\(\s*(?:'([^']*)'|"([^"]*)"|([A-Za-z_]\w*))""",
    );
    final constant = RegExp(
      r"""(?:static\s+)?const\s+(?:String\s+)?([A-Za-z_]\w*)\s*=\s*'([^']*)'""",
    );
    for (final file in Directory(
      'lib',
    ).listSync(recursive: true).whereType<File>()) {
      if (!file.path.endsWith('.dart') ||
          file.path.endsWith('.g.dart') ||
          file.path.endsWith('.freezed.dart') ||
          file.path.contains('l10n/generated')) {
        continue;
      }
      final code = file.readAsStringSync().replaceAll(
        RegExp(r'^\s*//.*$', multiLine: true),
        '',
      );
      final constants = {
        for (final match in constant.allMatches(code)) match[1]!: match[2]!,
      };
      for (final match in access.allMatches(code)) {
        final key = match[2] ?? match[3] ?? constants[match[4]];
        if (key == null || key.contains(r'$')) continue;
        values[key] = switch (match[1]) {
          'Bool' => false,
          'Int' => 1,
          'Double' => 1.25,
          'StringList' => <String>['value'],
          _ => key == 'aiProviders' ? '[]' : '{}',
        };
      }
    }
    expect(
      values.keys,
      containsAll([
        'locale',
        'chapterSplitCustomRules',
        'statisticsDashboardTiles',
        'userPrompts',
        'localMdxDictionaries',
        'aiTokenUsageMonthly',
        'readingAgentSyncDeviceId',
        'readingExperienceDiagnostics.v1',
      ]),
    );
    const localOnly = {
      'iapPurchaseStatus', 'iapLastCheckTime', 'readingAgentSyncDeviceId',
      'selfHostedProgressSyncEndpoint', 'selfHostedProgressSyncEnabled',
      'selfHostedProgressSyncToken', 'selfHostedProgressSyncTokenExpiresAt',
      'selfHostedProgressSyncCursor', 'readingAgentLocalBackfillStarts',
      'aiTokenUsageMonthly',
      'readingExperienceDiagnostics.v1',
      'aiExtractionConfig',
      'deviceDisplayProfile', 'themeMode', 'themeColor', 'trueDarkMode',
      // Consumed solely by the installation-local display migration.
      'eInkMode',
    };
    final prefs = await _initialize(values);
    final backup = await prefs.buildPrefsBackupMap();
    for (final key in values.keys) {
      expect(backup.containsKey(key), !localOnly.contains(key), reason: key);
    }
    await _initialize({});
    await prefs.applyPrefsBackupMap(backup);
    for (final key in values.keys.where((key) => !localOnly.contains(key))) {
      expect(prefs.prefs.containsKey(key), isTrue, reason: key);
    }
  });
}
