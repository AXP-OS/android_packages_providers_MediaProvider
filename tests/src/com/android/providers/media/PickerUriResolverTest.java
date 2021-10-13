/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.media;

import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.provider.MediaStore.MediaColumns.DISPLAY_NAME;
import static android.provider.MediaStore.MediaColumns.RELATIVE_PATH;

import static androidx.test.InstrumentationRegistry.getTargetContext;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.scan.MediaScannerTest;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileNotFoundException;

@RunWith(AndroidJUnit4.class)
public class PickerUriResolverTest {
    private static final String TAG = PickerUriResolverTest.class.getSimpleName();
    private static final File TEST_FILE = new File(Environment.getExternalStorageDirectory(),
            Environment.DIRECTORY_DOWNLOADS + "/" + TAG + System.currentTimeMillis() + ".jpeg");
    // UserId for which context and content resolver are set up such that TEST_FILE
    // file exists in this user's content resolver.
    private static final int TEST_USER = 20;

    private static Context sCurrentContext;
    private static TestPickerUriResolver sTestPickerUriResolver;
    private static Uri sTestPickerUri;

    private static class TestPickerUriResolver extends PickerUriResolver {
        TestPickerUriResolver(Context context) {
            super(context);
        }

        @Override
        protected Uri getRedactedUri(ContentResolver contentResolver, Uri uri) {
            // Cannot mock static method MediaStore.getRedactedUri(). Cannot mock implementation of
            // MediaStore.getRedactedUri as it depends on final methods which cannot be mocked as
            // well.
            return uri;
        }
    }

    @BeforeClass
    public static void setUp() throws Exception {
        // this test uses isolated context which requires these permissions to be granted
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(android.Manifest.permission.LOG_COMPAT_CHANGE,
                        android.Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        Manifest.permission.INTERACT_ACROSS_USERS);
        sCurrentContext = mock(Context.class);
        sTestPickerUriResolver = new TestPickerUriResolver(sCurrentContext);

        final Context otherUserContext = createOtherUserContext(TEST_USER);
        final Uri mediaStoreUriInOtherContext = createTestFileInContext(otherUserContext);

        sTestPickerUri = getPickerUriForId(ContentUris.parseId(mediaStoreUriInOtherContext),
                TEST_USER);
    }

    @AfterClass
    public static void tearDown() {
        TEST_FILE.delete();
    }

    @Test
    public void wrapProviderUriValid() throws Exception {
        final String providerSuffix = "authority/media/media_id";

        final Uri providerUriUserImplicit = Uri.parse("content://" + providerSuffix);

        final Uri providerUriUser0 = Uri.parse("content://0@" + providerSuffix);
        final Uri mediaUriUser0 = Uri.parse("content://media/picker/0/" + providerSuffix);

        final Uri providerUriUser10 = Uri.parse("content://10@" + providerSuffix);
        final Uri mediaUriUser10 = Uri.parse("content://media/picker/10/" + providerSuffix);

        assertThat(PickerUriResolver.wrapProviderUri(providerUriUserImplicit, 0))
                .isEqualTo(mediaUriUser0);
        assertThat(PickerUriResolver.wrapProviderUri(providerUriUser0, 0)).isEqualTo(mediaUriUser0);
        assertThat(PickerUriResolver.unwrapProviderUri(mediaUriUser0)).isEqualTo(providerUriUser0);

        assertThat(PickerUriResolver.wrapProviderUri(providerUriUserImplicit, 10))
                .isEqualTo(mediaUriUser10);
        assertThat(PickerUriResolver.wrapProviderUri(providerUriUser10, 10))
                .isEqualTo(mediaUriUser10);
        assertThat(PickerUriResolver.unwrapProviderUri(mediaUriUser10))
                .isEqualTo(providerUriUser10);
    }

    @Test
    public void wrapProviderUriInvalid() throws Exception {
        final String providerSuffixLong = "authority/media/media_id/another_media_id";
        final String providerSuffixShort = "authority/media";

        final Uri providerUriUserLong = Uri.parse("content://0@" + providerSuffixLong);
        final Uri mediaUriUserLong = Uri.parse("content://media/picker/0/" + providerSuffixLong);

        final Uri providerUriUserShort = Uri.parse("content://0@" + providerSuffixShort);
        final Uri mediaUriUserShort = Uri.parse("content://media/picker/0/" + providerSuffixShort);

        assertThrows(IllegalArgumentException.class,
                () -> PickerUriResolver.wrapProviderUri(providerUriUserLong, 0));
        assertThrows(IllegalArgumentException.class,
                () -> PickerUriResolver.unwrapProviderUri(mediaUriUserLong));

        assertThrows(IllegalArgumentException.class,
                () -> PickerUriResolver.unwrapProviderUri(mediaUriUserShort));
        assertThrows(IllegalArgumentException.class,
                () -> PickerUriResolver.wrapProviderUri(providerUriUserShort, 0));
    }

    @Test
    public void testOpenFile_mode_w() throws Exception {
        updateReadUriPermission(sTestPickerUri, /* grant */ true);
        try {
            sTestPickerUriResolver.openFile(sTestPickerUri, "w", /* signal */ null,
                    /* callingPid */ -1, /* callingUid */ -1);
            fail("Write is not supported for Picker Uris. uri: " + sTestPickerUri);
        } catch (SecurityException expected) {
            // expected
            assertThat(expected.getMessage()).isEqualTo("PhotoPicker Uris can only be accessed to"
                    + " read. Uri: " + sTestPickerUri);
        }
    }

    @Test
    public void testOpenFile_mode_rw() throws Exception {
        updateReadUriPermission(sTestPickerUri, /* grant */ true);
        try {
            sTestPickerUriResolver.openFile(sTestPickerUri, "rw", /* signal */ null,
                    /* callingPid */ -1, /* callingUid */ -1);
            fail("Read-Write is not supported for Picker Uris. uri: " + sTestPickerUri);
        } catch (SecurityException expected) {
            // expected
            assertThat(expected.getMessage()).isEqualTo("PhotoPicker Uris can only be accessed to"
                    + " read. Uri: " + sTestPickerUri);
        }
    }

    @Test
    public void testOpenFile_mode_invalid() throws Exception {
        updateReadUriPermission(sTestPickerUri, /* grant */ true);
        try {
            sTestPickerUriResolver.openFile(sTestPickerUri, "foo", /* signal */ null,
                    /* callingPid */ -1, /* callingUid */ -1);
            fail("Invalid mode should not be supported for openFile. uri: " + sTestPickerUri);
        } catch (IllegalArgumentException expected) {
            // expected
            assertThat(expected.getMessage()).isEqualTo("Bad mode: foo");
        }
    }

    @Test
    public void testPickerUriResolver_permissionDenied() throws Exception {
        updateReadUriPermission(sTestPickerUri, /* grant */ false);

        testOpenFile_permissionDenied(sTestPickerUri);
        testOpenTypedAssetFile_permissionDenied(sTestPickerUri);
        testQuery_permissionDenied(sTestPickerUri);
        testGetType_permissionDenied(sTestPickerUri);
    }

    @Test
    public void testPermissionGrantedOnOtherUserUri() throws Exception {
        // This test requires the uri to be valid in 2 different users, but the permission is
        // granted in one user only.
        final int otherUserId = 50;
        final Context otherUserContext = createOtherUserContext(otherUserId);
        final Uri mediaStoreUserInAnotherValidUser = createTestFileInContext(otherUserContext);
        final Uri grantedUri = getPickerUriForId(ContentUris.parseId(
                mediaStoreUserInAnotherValidUser), otherUserId);
        updateReadUriPermission(grantedUri, /* grant */ true);

        final Uri deniedUri = sTestPickerUri;
        updateReadUriPermission(deniedUri, /* grant */ false);

        testOpenFile_permissionDenied(deniedUri);
        testOpenTypedAssetFile_permissionDenied(deniedUri);
        testQuery_permissionDenied(deniedUri);
        testGetType_permissionDenied(deniedUri);
    }

    @Test
    public void testPickerUriResolver_userInvalid() throws Exception {
        final int invalidUserId = 40;

        final Uri inValidUserPickerUri = getPickerUriForId(/* id */ 1, invalidUserId);
        updateReadUriPermission(inValidUserPickerUri, /* grant */ true);

        // This method is called on current context when pickerUriResolver wants to get the content
        // resolver for another user.
        // NameNotFoundException is thrown when such a user does not exist.
        when(sCurrentContext.createPackageContextAsUser("android", /* flags= */ 0,
                UserHandle.of(invalidUserId))).thenThrow(
                        new PackageManager.NameNotFoundException());

        testOpenFileInvalidUser(inValidUserPickerUri);
        testOpenTypedAssetFileInvalidUser(inValidUserPickerUri);
        testQueryInvalidUser(inValidUserPickerUri);
        testGetTypeInvalidUser(inValidUserPickerUri);
    }

    @Test
    public void testPickerUriResolver_userValid() throws Exception {
        updateReadUriPermission(sTestPickerUri, /* grant */ true);

        testGetUserId(sTestPickerUri, UserHandle.of(TEST_USER));
        testOpenFile(sTestPickerUri);
        testOpenTypedAssetFile(sTestPickerUri);
        testQuery(sTestPickerUri);
        testGetType(sTestPickerUri, "image/jpeg");
    }

    private static Context createOtherUserContext(int user) throws Exception {
        final UserHandle userHandle = UserHandle.of(user);
        // For unit testing: IsolatedContext is the context of another User: user.
        // PickerUriResolver should correctly be able to call into other user's content resolver
        // from the current context.
        final Context otherUserContext = new MediaScannerTest.IsolatedContext(getTargetContext(),
                "modern", /* asFuseThread */ false);
        when(sCurrentContext.createPackageContextAsUser("android", /* flags= */ 0, userHandle)).
                thenReturn(otherUserContext);
        return otherUserContext;
    }

    private static Uri createTestFileInContext(Context context) throws Exception {
        TEST_FILE.createNewFile();
        final Uri uri = MediaStore.scanFile(context.getContentResolver(), TEST_FILE);
        assertThat(uri).isNotNull();
        return uri;
    }

    private void updateReadUriPermission(Uri uri, boolean grant) {
        final int permission = grant ? PERMISSION_GRANTED : PERMISSION_DENIED;
        when(sCurrentContext.checkUriPermission(uri, -1, -1,
                Intent.FLAG_GRANT_READ_URI_PERMISSION)).thenReturn(permission);
    }

    private static Uri getPickerUriForId(long id, int user) {
        return Uri.parse("content://media/picker/" + user + "/" + id);
    }

    private void testGetUserId(Uri uri, UserHandle userHandle) {
        assertThat(PickerUriResolver.getUserId(uri).toString()).isEqualTo(
                UserId.of(userHandle).toString());
    }

    private void testOpenFile(Uri uri) throws Exception {
        ParcelFileDescriptor pfd = sTestPickerUriResolver.openFile(uri, "r", /* signal */ null,
                /* callingPid */ -1, /* callingUid */ -1);

        assertThat(pfd).isNotNull();
    }

    private void testOpenTypedAssetFile(Uri uri) throws Exception {
        AssetFileDescriptor afd =  sTestPickerUriResolver.openTypedAssetFile(uri, "image/*",
                /* opts */ null, /* signal */ null, /* callingPid */ -1, /* callingUid */ -1);

        assertThat(afd).isNotNull();
    }

    private void testQuery(Uri uri) throws Exception {
        Cursor result = sTestPickerUriResolver.query(uri,
                /* projection */ new String[]{DISPLAY_NAME},
                /* queryArgs */ null, /* signal */ null, /* callingPid */ -1, /* callingUid */ -1);
        assertThat(result).isNotNull();
        assertThat(result.getCount()).isEqualTo(1);
        result.moveToFirst();
        assertThat(result.getString(0)).isEqualTo(TEST_FILE.getName());
    }

    private void testGetType(Uri uri, String expectedMimeType) throws Exception {
        String mimeType = sTestPickerUriResolver.getType(uri);
        assertThat(mimeType).isEqualTo(expectedMimeType);
    }

    private void testOpenFileInvalidUser(Uri uri) {
        try {
            sTestPickerUriResolver.openFile(uri, "r", /* signal */ null, /* callingPid */ -1,
                    /* callingUid */ -1);
            fail("Invalid user specified in the picker uri: " + uri);
        } catch (FileNotFoundException expected) {
            // expected
            assertThat(expected.getMessage()).isEqualTo("File not found due to unavailable content"
                    + " resolver for uri: " + uri
                    + " ; error: android.content.pm.PackageManager$NameNotFoundException");
        }
    }

    private void testOpenTypedAssetFileInvalidUser(Uri uri) throws Exception {
        try {
            sTestPickerUriResolver.openTypedAssetFile(uri, "image/*", /* opts */ null,
                    /* signal */ null, /* callingPid */ -1, /* callingUid */ -1);
            fail("Invalid user specified in the picker uri: " + uri);
        } catch (FileNotFoundException expected) {
            // expected
            assertThat(expected.getMessage()).isEqualTo("File not found due to unavailable content"
                    + " resolver for uri: " + uri
                    + " ; error: android.content.pm.PackageManager$NameNotFoundException");
        }
    }

    private void testQueryInvalidUser(Uri uri) throws Exception {
        Cursor result = sTestPickerUriResolver.query(uri, /* projection */ null,
                /* queryArgs */ null, /* signal */ null, /* callingPid */ -1, /* callingUid */ -1);
        assertThat(result).isNotNull();
        assertThat(result.getCount()).isEqualTo(0);
    }

    private void testGetTypeInvalidUser(Uri uri) throws Exception {
        try {
            sTestPickerUriResolver.getType(uri);
            fail("Invalid user specified in the picker uri: " + uri);
        } catch (IllegalArgumentException expected) {
            // expected
            assertThat(expected.getMessage()).isEqualTo("File not found due to unavailable "
                    + "content resolver for uri: " + uri
                    + " ; error: android.content.pm.PackageManager$NameNotFoundException");
        }
    }

    private void testOpenFile_permissionDenied(Uri uri) throws Exception {
        try {
            sTestPickerUriResolver.openFile(uri, "r", /* signal */ null, /* callingPid */ -1,
                    /* callingUid */ -1);
            fail("openFile should fail if the caller does not have permission grant on the picker"
                    + " uri: " + uri);
        } catch (SecurityException expected) {
            // expected
            assertThat(expected.getMessage()).isEqualTo("Calling uid ( -1 ) does not have"
                    + " permission to access picker uri: " + uri);
        }
    }

    private void testOpenTypedAssetFile_permissionDenied(Uri uri) throws Exception {
        try {
            sTestPickerUriResolver.openTypedAssetFile(uri, "image/*", /* opts */ null,
                    /* signal */ null, /* callingPid */ -1, /* callingUid */ -1);
            fail("openTypedAssetFile should fail if the caller does not have permission grant on"
                    + " the picker uri: " + uri);
        } catch (SecurityException expected) {
            // expected
            assertThat(expected.getMessage()).isEqualTo("Calling uid ( -1 ) does not have"
                    + " permission to access picker uri: " + uri);
        }
    }

    private void testQuery_permissionDenied(Uri uri) throws Exception {
        try {
            sTestPickerUriResolver.query(uri, /* projection */ null, /* queryArgs */ null,
                    /* signal */ null, /* callingPid */ -1, /* callingUid */ -1);
            fail("query should fail if the caller does not have permission grant on"
                    + " the picker uri: " + uri);
        } catch (SecurityException expected) {
            // expected
            assertThat(expected.getMessage()).isEqualTo("Calling uid ( -1 ) does not have"
                    + " permission to access picker uri: " + uri);
        }
    }

    private void testGetType_permissionDenied(Uri uri) throws Exception {
        // getType is unaffected by uri permission grants
        testGetType(uri, "image/jpeg");
    }
}