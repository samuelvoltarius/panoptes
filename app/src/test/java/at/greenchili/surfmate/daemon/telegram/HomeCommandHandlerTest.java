package at.greenchili.surfmate.daemon.telegram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HomeCommandHandlerTest {

    @Test
    public void befehlUndAliasseFuehrenZumHandler() {
        assertEquals("/startbildschirm", CommandRouter.canonical("/startbildschirm"));
        assertEquals("/startbildschirm", CommandRouter.canonical("/home"));
        assertEquals("/startbildschirm", CommandRouter.canonical("/homescreen"));
        assertEquals("/startbildschirm", CommandRouter.canonical("/Startbildschirm@MeinBot"));
        assertTrue(new HomeCommandHandler().canHandle("/startbildschirm"));
        assertFalse(new HomeCommandHandler().canHandle("/waechter"));
    }

    @Test
    public void rueckwegBrauchtKeineFernfreigabe() {
        // Der Weg zurück zum BYD-Startbildschirm darf nie von einer Fernfreigabe abhängen.
        assertNull(CommandRouter.requiredPermission("/startbildschirm"));
    }
}
