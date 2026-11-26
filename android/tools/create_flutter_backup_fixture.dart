import 'dart:convert';
import 'dart:io';

import 'package:archive/archive_io.dart';

Future<void> main() async {
  final root = Directory.systemTemp.createTempSync('anx-flutter-backup-');
  try {
    final databases = Directory('${root.path}/databases')..createSync();
    final database = File('${databases.path}/app_database.db');
    final schema = (jsonDecode(
            File('android/app/src/main/assets/schema_v22.json')
                .readAsStringSync()) as List)
        .cast<String>();
    final sql = [
      ...schema,
      'PRAGMA user_version = 22',
      "INSERT INTO tb_books (title, file_path, is_deleted) VALUES ('Flutter fixture', 'file/fixture.txt', 0)",
    ].join(';\n');
    final result = Process.runSync('sqlite3', [database.path, '$sql;']);
    if (result.exitCode != 0) {
      throw StateError('Cannot create fixture database: ${result.stderr}');
    }
    File('${databases.path}/other.db').writeAsStringSync('unrelated database');
    final books = Directory('${root.path}/file')..createSync();
    File('${books.path}/fixture.txt').writeAsStringSync('Flutter fixture');
    final prefs = File('${root.path}/anx_shared_prefs.json')
      ..writeAsStringSync(jsonEncode({
        '__prefsBackupVersion': 1,
        'readStyle': {
          'type': 'string',
          'value': jsonEncode({'fontSize': 1.8, 'lineHeight': 1.6}),
        },
        'ttsRate': {'type': 'double', 'value': 1.3},
      }));

    final output =
        File('android/app/src/androidTest/assets/flutter-encoder-backup.zip');
    output.parent.createSync(recursive: true);
    final encoder = ZipFileEncoder()..create(output.path);
    await encoder.addDirectory(databases);
    await encoder.addDirectory(databases);
    await encoder.addDirectory(books);
    await encoder.addFile(prefs);
    encoder.close();
  } finally {
    root.deleteSync(recursive: true);
  }
}
