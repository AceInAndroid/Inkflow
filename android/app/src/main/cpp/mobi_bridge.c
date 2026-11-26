#include <jni.h>
#include <mobi.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void fail(JNIEnv *env, const char *message) {
    jclass exception = (*env)->FindClass(env, "java/io/IOException");
    if (exception != NULL) (*env)->ThrowNew(env, exception, message);
}

static int write_part(JNIEnv *env, const char *directory, const char *name,
                      const MOBIPart *part) {
    size_t path_size = strlen(directory) + strlen(name) + 2;
    char *path = malloc(path_size);
    if (path == NULL) {
        fail(env, "Cannot allocate MOBI part path");
        return 0;
    }
    snprintf(path, path_size, "%s/%s", directory, name);
    FILE *output = fopen(path, "wb");
    free(path);
    if (output == NULL) {
        fail(env, "Cannot write MOBI part");
        return 0;
    }
    size_t written = fwrite(part->data, 1, part->size, output);
    int closed = fclose(output);
    if (written != part->size || closed != 0) {
        fail(env, "Cannot write MOBI part");
        return 0;
    }
    return 1;
}

static int is_bitmap(const MOBIPart *part) {
    return part->type == T_JPG || part->type == T_GIF ||
           part->type == T_PNG || part->type == T_BMP;
}

JNIEXPORT jobjectArray JNICALL
Java_com_aceinandroid_inkflow_MobiBook_extract(JNIEnv *env, jobject self,
                                                      jstring source, jstring destination) {
    (void) self;
    const char *source_path = (*env)->GetStringUTFChars(env, source, NULL);
    if (source_path == NULL) return NULL;
    const char *directory = (*env)->GetStringUTFChars(env, destination, NULL);
    if (directory == NULL) {
        (*env)->ReleaseStringUTFChars(env, source, source_path);
        return NULL;
    }

    MOBIData *book = mobi_init();
    MOBIRawml *raw = NULL;
    char *title = NULL;
    char *author = NULL;
    jobjectArray result = NULL;
    FILE *input = fopen(source_path, "rb");
    if (book == NULL || input == NULL) {
        fail(env, "Cannot open MOBI file");
        goto done;
    }
    MOBI_RET status = mobi_load_file(book, input);
    fclose(input);
    input = NULL;
    if (status != MOBI_SUCCESS) {
        fail(env, "Invalid MOBI file");
        goto done;
    }
    if (mobi_is_encrypted(book)) {
        fail(env, "Encrypted Kindle books are unsupported");
        goto done;
    }
    raw = mobi_init_rawml(book);
    if (raw == NULL || mobi_parse_rawml(raw, book) != MOBI_SUCCESS) {
        fail(env, "Cannot decode MOBI chapters");
        goto done;
    }
    size_t count = 0;
    for (MOBIPart *part = raw->markup; part != NULL; part = part->next) count++;
    size_t image_count = 0;
    for (MOBIPart *part = raw->resources; part != NULL; part = part->next) {
        if (is_bitmap(part)) image_count++;
    }
    if (count == 0 || count > 4096 || image_count > 8192) {
        fail(env, "MOBI has no readable chapters");
        goto done;
    }
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    if (string_class == NULL) goto done;
    result = (*env)->NewObjectArray(env, (jsize) (count + image_count + 5), string_class, NULL);
    if (result == NULL) goto done;
    title = mobi_meta_get_title(book);
    author = mobi_meta_get_author(book);
    const char *metadata[] = {title ? title : "", author ? author : "",
                              mobi_is_rawml_kf8(raw) || (book->mh && book->mh->text_encoding &&
                              *book->mh->text_encoding == MOBI_UTF8) ? "UTF-8" : "windows-1252"};
    for (int i = 0; i < 3; i++) {
        jstring value = (*env)->NewStringUTF(env, metadata[i]);
        if (value == NULL) goto done;
        (*env)->SetObjectArrayElement(env, result, i, value);
        (*env)->DeleteLocalRef(env, value);
    }
    const char *ncx_name = "";
    char ncx_file[40];
    for (MOBIPart *part = raw->resources; part != NULL; part = part->next) {
        if (part->type != T_NCX) continue;
        snprintf(ncx_file, sizeof(ncx_file), "resource%05zu.ncx", part->uid);
        if (!write_part(env, directory, ncx_file, part)) goto done;
        ncx_name = ncx_file;
        break;
    }
    jstring ncx_value = (*env)->NewStringUTF(env, ncx_name);
    if (ncx_value == NULL) goto done;
    (*env)->SetObjectArrayElement(env, result, 3, ncx_value);
    (*env)->DeleteLocalRef(env, ncx_value);
    char count_text[24];
    snprintf(count_text, sizeof(count_text), "%zu", count);
    jstring count_value = (*env)->NewStringUTF(env, count_text);
    if (count_value == NULL) goto done;
    (*env)->SetObjectArrayElement(env, result, 4, count_value);
    (*env)->DeleteLocalRef(env, count_value);
    size_t index = 0;
    for (MOBIPart *part = raw->markup; part != NULL; part = part->next, index++) {
        char name[40];
        snprintf(name, sizeof(name), "part%05zu.html", part->uid);
        if (!write_part(env, directory, name, part)) goto done;
        jstring value = (*env)->NewStringUTF(env, name);
        if (value == NULL) goto done;
        (*env)->SetObjectArrayElement(env, result, (jsize) index + 5, value);
        (*env)->DeleteLocalRef(env, value);
    }
    size_t image_index = 0;
    for (MOBIPart *part = raw->resources; part != NULL; part = part->next) {
        if (!is_bitmap(part)) continue;
        MOBIFileMeta meta = mobi_get_filemeta_by_type(part->type);
        char name[40];
        snprintf(name, sizeof(name), "resource%05zu.%s", part->uid, meta.extension);
        if (!write_part(env, directory, name, part)) goto done;
        jstring value = (*env)->NewStringUTF(env, name);
        if (value == NULL) goto done;
        (*env)->SetObjectArrayElement(env, result, (jsize) (count + image_index + 5), value);
        (*env)->DeleteLocalRef(env, value);
        image_index++;
    }

done:
    if (input != NULL) fclose(input);
    free(title);
    free(author);
    if (raw != NULL) mobi_free_rawml(raw);
    if (book != NULL) mobi_free(book);
    (*env)->ReleaseStringUTFChars(env, source, source_path);
    (*env)->ReleaseStringUTFChars(env, destination, directory);
    return (*env)->ExceptionCheck(env) ? NULL : result;
}
