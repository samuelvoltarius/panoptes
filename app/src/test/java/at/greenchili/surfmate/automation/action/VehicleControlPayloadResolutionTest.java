package at.greenchili.surfmate.automation.action;

import static org.junit.Assert.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;
import at.greenchili.surfmate.mqtt.VehicleControlCatalog;

import org.junit.Test;

/** Regression tests for payload templates used by multi-parameter climate controls. */
public class VehicleControlPayloadResolutionTest {

    @Test
    public void localActionVariableWinsOverBareGlobalVariableSyntax() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("temperature", 21);
        variables.put("zone", "driver");
        variables.put("payload", "${temperature}:${zone}");

        assertEquals("21:driver", VehicleControlAction.resolvePayload(
                variables.get("payload").toString(), variables));
    }

    @Test
    public void arithmeticRunsAfterLocalAndLiveInterpolation() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("temperature", 21);
        variables.put("payload", "${temperature}+1");

        assertEquals("22", VehicleControlAction.resolvePayload(
                variables.get("payload").toString(), variables));
    }

    @Test
    public void climateSubParameterUsesTheSameLocalInterpolationContract() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("operation", "temperature");
        variables.put("sub", "${operation}");

        assertEquals("temperature", VehicleControlAction.resolveParameter(
                variables.get("sub").toString(), variables));

        VehicleControlCatalog.ControlAction routed = VehicleControlCatalog.get("climate")
                .toAction(
                        VehicleControlAction.resolveParameter("${operation}", variables),
                        VehicleControlAction.resolvePayload("21+1", variables),
                        null);
        assertEquals(VehicleCommandRouter.ClimateSetTempCommand.class, routed.command.getClass());
        assertEquals(22.0,
                ((VehicleCommandRouter.ClimateSetTempCommand) routed.command).tempCelsius, 0.0);
    }

    @Test
    public void unresolvedReferenceRemainsLiteralAndNeverBecomesZero() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("payload", "${signal:notARealSignal}+1");

        assertEquals("${signal:notARealSignal}+1", VehicleControlAction.resolvePayload(
                variables.get("payload").toString(), variables));
    }

    @Test
    public void payloadCannotRecursivelyExpandItself() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("payload", "${payload}");

        assertEquals("${payload}", VehicleControlAction.resolvePayload(
                variables.get("payload").toString(), variables));
    }
}
