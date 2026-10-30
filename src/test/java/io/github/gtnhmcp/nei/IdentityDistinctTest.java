package io.github.gtnhmcp.nei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class IdentityDistinctTest {

    @Test
    void preservesDistinctCategoriesSharingAnIdButRemovesRepeatedInstances() {
        String alloySmelter = new String("gregtech.nei.GTNEIDefaultHandler");
        String fluidSolidifier = new String("gregtech.nei.GTNEIDefaultHandler");
        String centrifuge = new String("gregtech.nei.GTNEIDefaultHandler");

        List<String> result = IdentityDistinct
            .of(Arrays.asList(alloySmelter, fluidSolidifier, alloySmelter, centrifuge, fluidSolidifier));

        assertEquals(3, result.size());
        assertSame(alloySmelter, result.get(0));
        assertSame(fluidSolidifier, result.get(1));
        assertSame(centrifuge, result.get(2));
    }
}
