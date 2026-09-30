package com.clipforge.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

public final class NetworkGuard {
    private NetworkGuard(){}

    public static boolean isVpnActive(Context context) {
        try {
            ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if(cm==null)return false;
            Network active=cm.getActiveNetwork();
            if(active==null)return false;
            NetworkCapabilities caps=cm.getNetworkCapabilities(active);
            return caps!=null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (Exception ignored) {
            return false;
        }
    }
}
