"""Modbus-TCP-Orakel für die Wallbox-Gegenprobe (Kern 6, Stufe 2b) – nur Testwerkzeug, nie gegen echte Geräte.

Startet mit pymodbus 3.6.x (BSD-3-Clause, nicht Teil von Panoptes) einen Modbus-TCP-Server auf 127.0.0.1 mit der Registerbelegung
einer KEBA P40 (Unit 255) aus web/shared/wallbox-modbus-modelle.json. Panoptes-Java (WallboxModbusOrakelTest) liest und
schreibt dagegen; so wird der eigene Modbus-Client an einer unabhängigen Implementierung geprüft.

Start:  python -I modbus_orakel.py --port 15020
"""
import argparse
import asyncio
import logging

from pymodbus.datastore import ModbusSequentialDataBlock, ModbusServerContext, ModbusSlaveContext
from pymodbus.server import StartAsyncTcpServer


def u32(v):
    return [(v >> 16) & 0xFFFF, v & 0xFFFF]


class Halte(ModbusSequentialDataBlock):
    """Holding-Register mit KEBA-Logik: 5004 (mA, 0 = gesperrt) bestimmt Zustand 1000 und Leistung 1020 (mW)."""

    def setValues(self, address, values):
        super().setValues(address, values)
        ma = super().getValues(5004, 1)[0]
        super().setValues(1000, u32(3 if ma > 0 else 2))
        super().setValues(1020, u32(ma * 230 if ma > 0 else 0))


def kontext():
    hr = Halte(0, [0] * 6000)
    hr.setValues(1000, u32(2))
    hr.setValues(1004, u32(7))
    hr.setValues(1602, u32(0))
    hr.setValues(5004, [0])
    geraet = ModbusSlaveContext(hr=hr, ir=ModbusSequentialDataBlock(0, [0] * 100), zero_mode=True)
    return ModbusServerContext(slaves={255: geraet}, single=False)


if __name__ == "__main__":
    a = argparse.ArgumentParser()
    a.add_argument("--port", type=int, default=15020)
    o = a.parse_args()
    logging.basicConfig(level=logging.WARNING)
    print("Modbus-Orakel KEBA P40 auf 127.0.0.1:%d (Unit 255)" % o.port, flush=True)
    asyncio.run(StartAsyncTcpServer(context=kontext(), address=("127.0.0.1", o.port)))
