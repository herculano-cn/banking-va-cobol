import concurrent.futures
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

SCRIPT = Path(__file__).resolve().with_name('homologacao_mock.py')
RESOURCES = SCRIPT.parent / 'src/test/resources'
JAR = SCRIPT.parent / 'target/quarkus-app/quarkus-run.jar'


class HomologacaoTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir='/tmp')
        self.base = Path(self.tmp.name)
        self.raiz = self.base / 'privado'
        self.raiz.mkdir(mode=0o700)
        self.raiz.chmod(0o700)
        self.fixture = RESOURCES / 'mock-operacoes.dat'
        self.golden = RESOURCES / 'SAIDA.golden'
        self.jar = JAR

    def tearDown(self):
        self.tmp.cleanup()

    def rodar(self, *extra, env=None):
        cmd = [sys.executable, str(SCRIPT), '--somente-sinteticos', '--raiz', str(self.raiz),
               '--fixture', str(self.fixture), '--golden', str(self.golden), '--jar', str(self.jar), *extra]
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=20, env=env)
        dirs = list(self.raiz.iterdir())
        resultado = json.loads((dirs[0] / 'resultado.json').read_text()) if dirs else None
        return proc, dirs, resultado

    def java_falso(self, corpo):
        binario = self.base / 'bin'
        binario.mkdir(exist_ok=True)
        java = binario / 'java'
        java.write_text('#!/bin/sh\n' + corpo + '\n')
        java.chmod(0o700)
        jar = self.base / 'teste.jar'
        jar.write_bytes(b'jar sintetico')
        self.jar = jar
        env = os.environ.copy()
        env['PATH'] = str(binario)
        return env

    def test_jar_real_e_golden_independente(self):
        self.assertTrue(JAR.is_file(), 'empacote o JAR antes de rodar os testes')
        proc, dirs, result = self.rodar()
        self.assertEqual(proc.returncode, 0, (proc.stdout, proc.stderr, result))
        self.assertEqual(result['resultado'], 'IGUAL')
        self.assertEqual(result['rc_java'], 0)
        self.assertEqual(result['arquivos']['SAIDA']['sha256'], result['arquivos']['golden.bin']['sha256'])
        self.assertEqual(os.stat(dirs[0]).st_mode & 0o777, 0o700)
        self.assertEqual((dirs[0] / 'fixture.dat').read_bytes(), self.fixture.read_bytes())

    def test_golden_diferente_preserva_evidencias(self):
        self.golden = self.base / 'golden'
        self.golden.write_bytes(b'X' * 132)
        proc, dirs, result = self.rodar()
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(result['resultado'], 'DIFERENTE')
        self.assertTrue((dirs[0] / 'SAIDA').is_file())
        self.assertEqual(self.golden.read_bytes(), b'X' * 132)

    def test_ausentes_links_alias_e_fixture_vazia(self):
        for kind in ('ausente', 'symlink', 'alias', 'vazia', 'golden-invalido'):
            with self.subTest(kind=kind):
                self.fixture = RESOURCES / 'mock-operacoes.dat'
                self.golden = RESOURCES / 'SAIDA.golden'
                if kind == 'ausente':
                    self.fixture = self.base / 'ausente'
                elif kind == 'symlink':
                    self.fixture = self.base / 'link'
                    self.fixture.symlink_to(RESOURCES / 'mock-operacoes.dat')
                elif kind == 'alias':
                    self.golden = self.fixture
                elif kind == 'vazia':
                    self.fixture = self.base / 'vazia'
                    self.fixture.write_bytes(b'# comentario\n')
                else:
                    self.golden = self.base / 'invalido'
                    self.golden.write_bytes(b'bad')
                proc, dirs, result = self.rodar()
                self.assertNotEqual(proc.returncode, 0)
                self.assertEqual(result['resultado'], 'INCONCLUSIVO')
                for path in dirs:
                    shutil.rmtree(path)

    def test_raiz_insegura_ou_link_rejeitada(self):
        self.raiz.chmod(0o755)
        proc, dirs, _ = self.rodar()
        self.assertNotEqual(proc.returncode, 0)
        self.assertFalse(dirs)
        self.raiz.chmod(0o700)
        link = self.base / 'link-root'
        link.symlink_to(self.raiz)
        self.raiz = link
        proc, dirs, _ = self.rodar()
        self.assertNotEqual(proc.returncode, 0)
        self.assertFalse(dirs)

    def test_processo_rc_falha_ou_log_ausente(self):
        env = self.java_falso('exit 12')
        proc, dirs, result = self.rodar(env=env)
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(result['rc_java'], 12)
        self.assertEqual(result['resultado'], 'INCONCLUSIVO')
        self.assertTrue((dirs[0] / 'stderr').is_file())

    def test_log_ausente_mesmo_com_rc_zero(self):
        env = self.java_falso('exit 0')
        proc, _, result = self.rodar(env=env)
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(result['rc_java'], 0)
        self.assertIn('erro', result['arquivos']['LOGFILE'])

    def test_timeout_e_ambiente_limpo(self):
        env = self.java_falso('test -z "$EXTRATO_DB2_PASSWORD" || exit 42\nexec /bin/sleep 10')
        env['EXTRATO_DB2_PASSWORD'] = 'segredo-que-nao-deve-ser-passado'
        proc, dirs, result = self.rodar('--timeout', '1', env=env)
        self.assertNotEqual(proc.returncode, 0)
        self.assertTrue(result['timeout'])
        self.assertNotEqual(result['rc_java'], 42)
        self.assertTrue((dirs[0] / 'resultado.json').is_file())

    def test_saida_symlink_nao_trunca_alvo(self):
        alvo = self.base / 'aprovado'
        alvo.write_bytes(b'original')
        env = self.java_falso('/bin/ln -s ' + str(alvo) + ' SAIDA\nexit 0')
        proc, _, result = self.rodar(env=env)
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(result['resultado'], 'INCONCLUSIVO')
        self.assertEqual(alvo.read_bytes(), b'original')

    def test_opt_in_obrigatorio_sem_criar_evidencia(self):
        cmd = [sys.executable, str(SCRIPT), '--raiz', str(self.raiz), '--fixture', str(self.fixture),
               '--golden', str(self.golden), '--jar', str(self.jar)]
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn('--somente-sinteticos', proc.stderr)
        self.assertEqual(list(self.raiz.iterdir()), [])

    def test_execucoes_concorrentes_preservam_saida_anterior(self):
        antiga = self.raiz / 'saida-anterior'
        antiga.write_bytes(b'anterior sintetica')
        cmd = [sys.executable, str(SCRIPT), '--somente-sinteticos', '--raiz', str(self.raiz),
               '--fixture', str(self.fixture), '--golden', str(self.golden), '--jar', str(self.jar)]
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            processos = list(pool.map(lambda _: subprocess.run(cmd, capture_output=True, text=True, timeout=30), range(3)))
        self.assertEqual([p.returncode for p in processos], [0, 0, 0], [(p.stdout, p.stderr) for p in processos])
        dirs = [p for p in self.raiz.iterdir() if p.is_dir()]
        self.assertEqual(len(dirs), 3)
        self.assertEqual(antiga.read_bytes(), b'anterior sintetica')
        for pasta in dirs:
            resultado = json.loads((pasta / 'resultado.json').read_text())
            self.assertEqual(resultado['resultado'], 'IGUAL')
            for nome in ('fixture.dat', 'golden.bin', 'SAIDA', 'LOGFILE', 'stdout', 'stderr'):
                dados = (pasta / nome).read_bytes()
                self.assertEqual(resultado['arquivos'][nome], {'bytes': len(dados), 'sha256': hashlib.sha256(dados).hexdigest()})
                self.assertEqual((pasta / nome).stat().st_mode & 0o777, 0o600)
            self.assertEqual(resultado['arquivos']['jar']['sha256'], hashlib.sha256(self.jar.read_bytes()).hexdigest())

    def test_colisao_nao_altera_diretorio_existente(self):
        spec = importlib.util.spec_from_file_location('homologacao_mock_colisao', SCRIPT)
        modulo = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(modulo)
        pasta = self.raiz / ('homologacao-' + 'a' * 32)
        pasta.mkdir()
        anterior = pasta / 'SAIDA'
        anterior.write_bytes(b'preservar')
        args = mock.Mock(raiz=str(self.raiz))
        with mock.patch.object(modulo.secrets, 'token_hex', return_value='a' * 32):
            with self.assertRaises(FileExistsError):
                modulo.executar(args)
        self.assertEqual(anterior.read_bytes(), b'preservar')
        self.assertEqual(list(pasta.iterdir()), [anterior])

    def test_rc_4_e_12_preservam_capturas_e_parciais(self):
        for rc in (4, 12):
            with self.subTest(rc=rc):
                env = self.java_falso("printf parcial > SAIDA\nprintf logparcial > LOGFILE\nprintf saida\nprintf erro >&2\nexit " + str(rc))
                proc, dirs, resultado = self.rodar(env=env)
                self.assertNotEqual(proc.returncode, 0)
                self.assertEqual(resultado['rc_java'], rc)
                self.assertEqual(resultado['resultado'], 'INCONCLUSIVO')
                for nome, conteudo in (('SAIDA', b'parcial'), ('LOGFILE', b'logparcial'), ('stdout', b'saida'), ('stderr', b'erro')):
                    self.assertEqual((dirs[0] / nome).read_bytes(), conteudo)
                    self.assertEqual(resultado['arquivos'][nome]['sha256'], hashlib.sha256(conteudo).hexdigest())
                shutil.rmtree(dirs[0])

    def test_alias_hardlink_e_link_em_ancestral_sao_rejeitados(self):
        self.fixture = self.base / 'fixture'
        self.fixture.write_bytes((RESOURCES / 'mock-operacoes.dat').read_bytes())
        hardlink = self.base / 'alias'
        os.link(self.fixture, hardlink)
        self.golden = hardlink
        proc, _, resultado = self.rodar()
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(resultado['resultado'], 'INCONCLUSIVO')
        self.assertIn('distintos', resultado['erro'])
        for pasta in self.raiz.iterdir():
            shutil.rmtree(pasta)
        link = self.base / 'diretorio-link'
        link.symlink_to(RESOURCES, target_is_directory=True)
        self.fixture = link / 'mock-operacoes.dat'
        self.golden = RESOURCES / 'SAIDA.golden'
        proc, _, resultado = self.rodar()
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn('link simbólico', resultado['erro'])

    def test_timeout_mata_filho_no_grupo_e_preserva_parcial(self):
        env = self.java_falso("printf parcial > SAIDA\n(/bin/sleep 2; printf alterada > SAIDA) >/dev/null 2>&1 &\nexec /bin/sleep 10")
        proc, dirs, resultado = self.rodar('--timeout', '1', env=env)
        self.assertNotEqual(proc.returncode, 0)
        self.assertTrue(resultado['timeout'])
        time.sleep(3)
        self.assertEqual((dirs[0] / 'SAIDA').read_bytes(), b'parcial')

    def test_falha_de_preparacao_preserva_snapshot(self):
        self.golden = self.base / 'nao-existe'
        proc, dirs, resultado = self.rodar()
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(resultado['resultado'], 'INCONCLUSIVO')
        self.assertIsNone(resultado['rc_java'])
        self.assertEqual((dirs[0] / 'fixture.dat').read_bytes(), self.fixture.read_bytes())
        self.assertEqual(resultado['arquivos']['fixture.dat']['sha256'], hashlib.sha256(self.fixture.read_bytes()).hexdigest())
        self.assertFalse((dirs[0] / 'SAIDA').exists())

    def test_log_ausente_ou_invalido_com_saida_igual(self):
        for log in ('', 'printf invalido > LOGFILE'):
            with self.subTest(log=log):
                env = self.java_falso('/bin/cp golden.bin SAIDA\n' + log + '\nexit 0')
                proc, dirs, resultado = self.rodar(env=env)
                self.assertNotEqual(proc.returncode, 0)
                self.assertEqual(resultado['resultado'], 'INCONCLUSIVO')
                self.assertEqual(resultado['rc_java'], 0)
                self.assertEqual((dirs[0] / 'SAIDA').read_bytes(), self.golden.read_bytes())
                shutil.rmtree(dirs[0])

    def test_resultado_hardlink_ou_fifo_recusado(self):
        for comando in ('/bin/ln golden.bin SAIDA', '/usr/bin/mkfifo SAIDA'):
            with self.subTest(comando=comando):
                env = self.java_falso(comando + '\nexit 0')
                proc, dirs, resultado = self.rodar(env=env)
                self.assertNotEqual(proc.returncode, 0)
                self.assertEqual(resultado['resultado'], 'INCONCLUSIVO')
                self.assertIn('erro', resultado['arquivos']['SAIDA'])
                shutil.rmtree(dirs[0])

    def test_fixture_acima_do_limite_recusada(self):
        self.fixture = self.base / 'grande'
        with self.fixture.open('wb') as arquivo:
            arquivo.truncate(8 * 1024 * 1024 + 1)
        proc, dirs, resultado = self.rodar()
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(resultado['resultado'], 'INCONCLUSIVO')
        self.assertIn('maior que 8 MiB', resultado['erro'])
        self.assertFalse((dirs[0] / 'SAIDA').exists())

    def test_ambiente_e_argumentos_do_processo_sao_restritos(self):
        env = self.java_falso('/usr/bin/env\nprintf "argumento=%s\\n" "$@"\nexit 12')
        env.update({'JAVA_TOOL_OPTIONS': '-Dsegredo=sintetico', 'EXTRATO_DB2_URL': 'sintetico',
                    'EXTRATO_DB2_PASSWORD': 'sintetico', 'CUSTOM_SECRET': 'sintetico'})
        proc, dirs, resultado = self.rodar(env=env)
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(resultado['rc_java'], 12)
        linhas = (dirs[0] / 'stdout').read_text().splitlines()
        variaveis = dict(linha.split('=', 1) for linha in linhas if not linha.startswith('argumento='))
        self.assertEqual(variaveis, {'HOME': str(dirs[0]), 'TMPDIR': str(dirs[0]), 'LANG': 'C',
                                     'LC_ALL': 'C', 'TZ': 'UTC', 'PWD': str(dirs[0])})
        argumentos = [linha.removeprefix('argumento=') for linha in linhas if linha.startswith('argumento=')]
        self.assertEqual(argumentos, ['-jar', str(self.jar), '--fonte', 'mock', '--mock', str(dirs[0] / 'fixture.dat'),
                                      '--saida', str(dirs[0] / 'SAIDA'), '--log', str(dirs[0] / 'LOGFILE')])

    def test_raiz_com_bits_especiais_recusada(self):
        self.raiz.chmod(0o2700)
        proc, dirs, _ = self.rodar()
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(dirs, [])

    def test_captura_truncada_nao_pode_ser_aprovada(self):
        env = self.java_falso(
            '/bin/cp golden.bin SAIDA\n'
            "printf '120000%-126s' '   PGMEXTRT INFO  INICIO DO PROCESSAMENTO DO EXTRATO DE CREDITO' > LOGFILE\n"
            "/usr/bin/python3 -c 'import os,signal; signal.signal(signal.SIGXFSZ,signal.SIG_IGN); "
            "exec(\"try:\\n os.write(1,b\\x27x\\x27*9437184)\\n os.write(1,b\\x27x\\x27)\\nexcept OSError:\\n pass\")'\nexit 0")
        proc, dirs, resultado = self.rodar(env=env)
        self.assertEqual((dirs[0] / 'stdout').stat().st_size, 8 * 1024 * 1024)
        self.assertEqual(resultado['rc_java'], 0)
        self.assertNotEqual(proc.returncode, 0, resultado)
        self.assertEqual(resultado['resultado'], 'INCONCLUSIVO')

    def test_filho_no_mesmo_grupo_nao_pode_alterar_evidencia_apos_sucesso(self):
        env = self.java_falso(
            '/bin/cp golden.bin SAIDA\n'
            "printf '120000%-126s' '   PGMEXTRT INFO  INICIO DO PROCESSAMENTO DO EXTRATO DE CREDITO' > LOGFILE\n"
            "(/bin/sleep 2; printf alterada > SAIDA) >/dev/null 2>&1 &\nexit 0")
        proc, dirs, resultado = self.rodar(env=env)
        self.assertNotEqual(proc.returncode, 0, resultado)
        self.assertEqual(resultado['resultado'], 'INCONCLUSIVO')
        self.assertTrue(resultado['descendentes_ativos'])
        time.sleep(3)
        self.assertEqual((dirs[0] / 'SAIDA').read_bytes(), self.golden.read_bytes())

    def test_captura_stderr_no_limite_e_inconclusiva(self):
        env = self.java_falso(
            '/bin/cp golden.bin SAIDA\n'
            "printf '120000%-126s' '   PGMEXTRT INFO  INICIO DO PROCESSAMENTO DO EXTRATO DE CREDITO' > LOGFILE\n"
            "/usr/bin/python3 -c 'import os; os.write(2,b\"x\"*8388608)'\nexit 0")
        proc, dirs, resultado = self.rodar(env=env)
        self.assertEqual((dirs[0] / 'stderr').stat().st_size, 8 * 1024 * 1024)
        self.assertEqual(resultado['rc_java'], 0)
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(resultado['resultado'], 'INCONCLUSIVO')

    def test_inspecao_proc_falha_fecha_grupo(self):
        spec = importlib.util.spec_from_file_location('homologacao_mock_proc', SCRIPT)
        modulo = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(modulo)
        processo = subprocess.Popen(['/bin/sleep', '10'], preexec_fn=os.setsid)
        resultado = {'timeout': False, 'descendentes_ativos': False, 'rc_java': None}
        try:
            with mock.patch.object(modulo, 'membros_ativos', side_effect=OSError('proc indisponível')):
                with self.assertRaises(OSError):
                    modulo.aguardar_processo(processo, 1, resultado)
            self.assertIsNotNone(resultado['rc_java'])
        finally:
            if processo.poll() is None:
                processo.kill()
                processo.wait()


if __name__ == '__main__':
    unittest.main()
