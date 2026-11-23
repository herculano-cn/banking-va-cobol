#!/usr/bin/env python3
# Historical only: targets the old CLI and defective byte-for-byte COBOL behavior.
# This is not a compatibility or acceptance test for the corrected Java batch.
"""Comparação de processos reais, com dados sintéticos e mock COBOL original."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / "extrato-java/target/quarkus-app/quarkus-run.jar"
RESOURCES = ROOT / "extrato-java/src/test/resources"
SOURCES = ("PGMEXTRT.cbl", "PGMFMTEXT.cbl", "PGMGRVEXT.cbl", "PGMLECRD_mock.cbl")
ENV = {"PATH": os.environ.get("PATH", ""), "HOME": "/tmp", "LC_ALL": "C"}
# Permite instalações de compilador isoladas sem herdar configuração COBOL/DB2.
for key in ("LD_LIBRARY_PATH", "COB_CONFIG_DIR", "COB_COPY_DIR"):
    if key in os.environ:
        ENV[key] = os.environ[key]


def run(args, cwd):
    return subprocess.run(args, cwd=cwd, env=ENV, capture_output=True, timeout=60)


def operation(index, value="123.45", quantity="12", paid="3"):
    return f"0042123456|OP{index:04d}|CPE|TESTE {index}|{value}|{quantity}|{paid}|{value}|20261231|A\n"


class Diferencial(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not JAR.is_file():
            raise RuntimeError(f"JAR ausente: {JAR}; execute mvn package")
        for command in ("cobc", "java", "awk"):
            if not shutil.which(command):
                raise RuntimeError(f"Comando obrigatório ausente: {command}")
        print(run(["cobc", "-V"], ROOT).stdout.decode(errors="replace").splitlines()[0], flush=True)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="extrato-diferencial-", dir="/tmp")
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        self.cobol = self.work / "cobol"
        self.java = self.work / "java"
        self.cobol.mkdir()
        self.java.mkdir()
        for name in SOURCES:
            shutil.copyfile(ROOT / name, self.cobol / name)

    def compile_cobol(self, data):
        fixture = self.work / "mock.dat"
        fixture.write_bytes(data)
        generated = run(["awk", "-f", str(ROOT / "scripts/gen-mock-from-dat.awk"), str(fixture)], self.work)
        self.assertEqual(generated.returncode, 0, generated.stderr.decode(errors="replace"))
        (self.cobol / "mock-generated.inc").write_bytes(generated.stdout)
        result = run(["cobc", "-std=mvs", "-x", "-o", "extrato", *SOURCES], self.cobol)
        self.assertEqual(result.returncode, 0, result.stderr.decode(errors="replace"))
        return fixture

    def java_run(self, fixture, *args):
        return run(["java", "-jar", str(JAR), "--fonte", "mock", "--mock", str(fixture),
                    "--saida", "SAIDA", "--log", "LOGFILE", *args], self.java)

    def compare(self, data, details, golden=False):
        fixture = self.compile_cobol(data)
        cobol = run(["./extrato"], self.cobol)
        java = self.java_run(fixture)
        for name, result in (("COBOL", cobol), ("Java", java)):
            self.assertEqual(result.returncode, 0, f"{name}: {result.stderr!r}")
        cobol_saida = (self.cobol / "SAIDA").read_bytes()
        java_saida = (self.java / "SAIDA").read_bytes()
        self.assertEqual(len(cobol_saida), (details + 2) * 132)
        self.assertEqual(cobol_saida, java_saida, "SAIDA difere byte a byte")
        if golden:
            self.assertEqual(cobol_saida, (RESOURCES / "SAIDA.golden").read_bytes())
        cobol_log = (self.cobol / "LOGFILE").read_bytes()
        java_log = (self.java / "LOGFILE").read_bytes()
        self.assertEqual(len(cobol_log), 132)
        self.assertEqual(len(java_log), 132)
        for log in (cobol_log, java_log):
            self.assertTrue(log[:6].isdigit(), repr(log[:8]))
        self.assertEqual(cobol_log[6:], java_log[6:], "LOGFILE difere além da hora HHMMSS")

    def test_golden_original(self):
        self.compare((RESOURCES / "mock-operacoes.dat").read_bytes(), 4, golden=True)

    def test_mock_vazio_usa_fallback(self):
        self.compare(b"", 2)

    def test_uma_operacao_consumida(self):
        self.compare(operation(1).encode(), 0)

    def test_multiplas_operacoes(self):
        self.compare("".join(operation(i) for i in range(1, 4)).encode(), 2)

    def test_linhas_ignoradas_crlf_e_aspas(self):
        data = (b"# comentario\r\n\r\nmalformada|ignorada\r\n"
                + operation(1).encode().replace(b"\n", b"\r\n")
                + b"1234567890|AB'CD|CPE|D'AGUA|12.34|7|3|9.87|20260731|A|EXTRA\r\n")
        self.compare(data, 1)

    def test_status_modalidades_datas(self):
        rows = [
            "0000000000|CONSUMIDO|CPE|NAO EXIBIDO|0|0|0|0|20260101|A",
            "0000000001|ATIVO|CPE|CREDITO PESSOAL|1.23|1|0|1.23|20260228|A",
            "9999999999|INATIVO|FIN|FINANCIAMENTO|2.34|2|1|2.34|20000229|I",
            "0042123456|QUITADO|RFI|IMOBILIARIO|3.45|3|2|3.45|20261231|Q",
            "0042123456|R|ZZZ|OUTROS|4.56|4|3|4.56|2026-06-|R",
            "0042123456|DESCONHECIDO|ABC|DESCONHECIDO|5.67|5|4|5.67|00000000|X",
        ]
        self.compare(("\n".join(rows) + "\n").encode("ascii"), 5)

    def test_limites_numericos_e_total(self):
        data = operation(1, "0.00", "0", "0") + operation(2, "99999999999.99", "999", "999")
        data += operation(3, "99999999999.99", "999", "0")
        self.compare(data.encode(), 2)

    def test_truncamento_numerico_e_campos_longos(self):
        data = (operation(1)
                + "1234567890123|1234567890123456|ABCD|1234567890123456789012|"
                  "-100000000000.987|-1001.9|-2.7|-100000000000.987|20260615123|AT\n")
        self.compare(data.encode("ascii"), 1)

    def test_limite_50_operacoes(self):
        self.compare("".join(operation(i) for i in range(50)).encode(), 49)

    def test_erro_abrir_saida(self):
        fixture = self.compile_cobol(operation(1).encode())
        (self.cobol / "SAIDA").mkdir()
        (self.java / "SAIDA").mkdir()
        cobol = run(["./extrato"], self.cobol)
        java = self.java_run(fixture)
        self.assertEqual(cobol.returncode, 12, repr(cobol.stderr))
        self.assertEqual(java.returncode, 12, repr(java.stderr))
        self.assertTrue((self.cobol / "SAIDA").is_dir())
        self.assertTrue((self.java / "SAIDA").is_dir())

    def test_erro_abrir_log_nao_comparavel(self):
        fixture = self.compile_cobol(operation(1).encode())
        for directory in (self.cobol, self.java):
            (directory / "LOGFILE").mkdir()
        cobol = run(["./extrato"], self.cobol)
        java = self.java_run(fixture)
        self.assertEqual(cobol.returncode, 12, repr(cobol.stderr))
        self.assertEqual(java.returncode, 12, repr(java.stderr))
        self.assertIn(b"Falha no batch", java.stderr)
        for directory in (self.cobol, self.java):
            self.assertTrue((directory / "LOGFILE").is_dir())
        cobol_saida = (self.cobol / "SAIDA").read_bytes()
        self.assertEqual(len(cobol_saida), 4 * 132)
        self.assertIn(b"BANCO AIR", cobol_saida[:132])
        self.assertEqual(cobol_saida[132:264], cobol_saida[264:396])
        self.assertIn(b"TOTAL OPERACOES", cobol_saida[396:])
        self.assertEqual((self.java / "SAIDA").read_bytes(), b"")

    def test_erro_entrada_java_nao_comparavel(self):
        fixture = self.compile_cobol(operation(1).encode())
        java = self.java_run(fixture, "--data", "invalida")
        self.assertEqual(java.returncode, 12)
        self.assertIn(b"Falha no batch", java.stderr)
        self.assertFalse((self.java / "SAIDA").exists())
        self.assertFalse((self.java / "LOGFILE").exists())

    def test_numero_invalido(self):
        fixture = self.work / "invalid.dat"
        fixture.write_text(operation(1, "INVALIDO"), encoding="ascii")
        generated = run(["awk", "-f", str(ROOT / "scripts/gen-mock-from-dat.awk"), str(fixture)], self.work)
        self.assertEqual(generated.returncode, 0)
        (self.cobol / "mock-generated.inc").write_bytes(generated.stdout)
        cobol = run(["cobc", "-std=mvs", "-x", "-o", "extrato", *SOURCES], self.cobol)
        java = self.java_run(fixture)
        self.assertNotEqual(cobol.returncode, 0, "COBOL deve rejeitar literal inválido na compilação")
        self.assertTrue(cobol.stderr)
        self.assertEqual(java.returncode, 12)
        self.assertIn(b"mock, linha 1", java.stderr)
        self.assertEqual((self.java / "SAIDA").read_bytes(), b"")
        self.assertEqual(len((self.java / "LOGFILE").read_bytes()), 264)

    def test_51_operacoes_java_rejeita(self):
        # 51 entradas ultrapassam OCCURS 50: não executamos COBOL com acesso fora da tabela.
        fixture = self.work / "mock51.dat"
        fixture.write_text("".join(operation(i) for i in range(51)), encoding="ascii")
        java = self.java_run(fixture)
        self.assertEqual(java.returncode, 12)
        self.assertIn(b"excede 50", java.stderr)
        self.assertEqual(len((self.java / "SAIDA").read_bytes()), 50 * 132)
        self.assertEqual(len((self.java / "LOGFILE").read_bytes()), 264)


if __name__ == "__main__":
    unittest.main(verbosity=2)
