/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNetworkCapabilities;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.List;

/**
 * The route the saves really use. Through a VPN, the phone's own lookup isn't where the socket
 * goes, and a fake-IP client running as one answers every name from its own pool. MediaUrlPolicyTest
 * builds its own routes, so only this test sees the one a save gets.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SaveRouteTest {

    @Test
    public void aVpnOnTheActiveNetworkMakesTheRouteIndirect() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network active = manager.getActiveNetwork();
        assertNotNull("no active network to put a VPN on", active);
        URL cdn = new URL("https://scontent.xx.fbcdn.net/v/t42.1790-2/461234_n.mp4");

        NetworkCapabilities capabilities = ShadowNetworkCapabilities.newInstance();
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        shadowOf(manager).setNetworkCapabilities(active, capabilities);
        assertTrue("Wi-Fi alone goes straight to the answer", MediaDownload.policyFor(context).route.direct(cdn));

        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_VPN);
        shadowOf(manager).setNetworkCapabilities(active, capabilities);
        assertFalse("a VPN carries the socket, so the lookup isn't the gate",
                MediaDownload.policyFor(context).route.direct(cdn));

        shadowOf(capabilities).removeTransportType(NetworkCapabilities.TRANSPORT_VPN);
        shadowOf(manager).setNetworkCapabilities(active, capabilities);
        assertTrue("with the VPN gone the route is direct again", MediaDownload.policyFor(context).route.direct(cdn));
    }

    /**
     * The other half of the same route: a Wi-Fi or global proxy resolves the name itself, so the
     * phone's lookup isn't the gate there either. MediaUrlPolicyTest holds proxied() on its own;
     * this holds the route a save gets.
     */
    @Test
    public void aProxyForTheAddressMakesTheRouteIndirect() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        URL cdn = new URL("https://scontent.xx.fbcdn.net/v/t42.1790-2/461234_n.mp4");
        ProxySelector saved = ProxySelector.getDefault();
        try {
            ProxySelector.setDefault(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    return Collections.singletonList(new Proxy(Proxy.Type.HTTP,
                            InetSocketAddress.createUnresolved("proxy.example", 8080)));
                }

                @Override
                public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                }
            });
            assertFalse("a proxy resolves the name, so the lookup isn't the gate",
                    MediaDownload.policyFor(context).route.direct(cdn));
        } finally {
            ProxySelector.setDefault(saved);
        }
        assertTrue("with the proxy gone the route is direct again", MediaDownload.policyFor(context).route.direct(cdn));
    }
}
