package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ScreenDeterrentAssetTest {

    @Test
    public void onlyFinalAssetsInsideTheManagedDirectoryAreAccepted() {
        assertTrue(ScreenDeterrentAsset.isAllowedPath(
                "/data/user_de/0/com.android.shell/surfmate/.surfmate/screen_deterrent_asset.123.mp4"));
        assertFalse(ScreenDeterrentAsset.isAllowedPath(
                "/data/user_de/0/com.android.shell/surfmate/.surfmate/screen_deterrent_asset.upload.tmp"));
        assertFalse(ScreenDeterrentAsset.isAllowedPath(
                "/data/user_de/0/com.android.shell/surfmate/.surfmate/SCREEN_DETERRENT_ASSET.123.mp4"));
        assertFalse(ScreenDeterrentAsset.isAllowedPath(
                "/data/user_de/0/com.android.shell/surfmate/.surfmate/../secret.mp4"));
        assertFalse(ScreenDeterrentAsset.isAllowedPath("/etc/passwd"));
    }
}
