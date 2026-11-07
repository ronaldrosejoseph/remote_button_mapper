package com.example.remotebuttonmapper;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class MainActivityRemoveServiceTest {

    @Test
    public void testRemoveService_survivesOtherService() {
        String component = "com.example.remotebuttonmapper/com.example.remotebuttonmapper.ButtonMapperService";
        String otherService = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService";
        String serviceList = otherService + ":" + component;

        String result = MainActivity.removeServiceFromList(serviceList, component);
        assertEquals(otherService, result);
    }

    @Test
    public void testRemoveService_survivesOtherServiceWhenFirst() {
        String component = "com.example.remotebuttonmapper/com.example.remotebuttonmapper.ButtonMapperService";
        String otherService = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService";
        String serviceList = component + ":" + otherService;

        String result = MainActivity.removeServiceFromList(serviceList, component);
        assertEquals(otherService, result);
    }

    @Test
    public void testRemoveService_multipleServicesPreserved() {
        String component = "com.example.remotebuttonmapper/com.example.remotebuttonmapper.ButtonMapperService";
        String service1 = "com.test.service1/Service1";
        String service2 = "com.test.service2/Service2";
        String serviceList = service1 + ":" + component + ":" + service2;

        String result = MainActivity.removeServiceFromList(serviceList, component);
        assertEquals(service1 + ":" + service2, result);
    }

    @Test
    public void testRemoveService_onlyTargetService() {
        String component = "com.example.remotebuttonmapper/com.example.remotebuttonmapper.ButtonMapperService";
        String result = MainActivity.removeServiceFromList(component, component);
        assertEquals("", result);
    }

    @Test
    public void testRemoveService_nullOrEmpty() {
        String component = "com.example.remotebuttonmapper/com.example.remotebuttonmapper.ButtonMapperService";
        assertEquals("", MainActivity.removeServiceFromList(null, component));
        assertEquals("", MainActivity.removeServiceFromList("", component));
    }
}

