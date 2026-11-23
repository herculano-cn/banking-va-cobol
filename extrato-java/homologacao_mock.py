#!/usr/bin/env python3
# Historical only: invokes the old CLI and expects the legacy report/log contract.
# Do not use this wrapper to accept the corrected Java batch without redesign.
"""Execução opt-in de homologação local exclusivamente com dados sintéticos."""
import argparse
import hashlib
import json
import os
import resource
import secrets
import shutil
import signal
import stat
import subprocess
import sys
import time

LIMITE = 8 * 1024 * 1024


def abrir_sem_links(caminho, diretorio=False):
    """Percorre componentes com dir_fd e O_NOFOLLOW, inclusive o último."""
    if not os.path.isabs(caminho) or any(p in ('.', '..') for p in caminho.split('/')):
        raise ValueError('caminho deve ser absoluto e não conter . ou ..')
    partes = [p for p in caminho.split('/') if p]
    fd = os.open('/', os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC)
    try:
        for i, parte in enumerate(partes):
            anterior = os.stat(parte, dir_fd=fd, follow_symlinks=False)
            if stat.S_ISLNK(anterior.st_mode):
                raise ValueError('link simbólico não permitido')
            flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK
            if i < len(partes) - 1 or diretorio:
                flags |= os.O_DIRECTORY
            novo = os.open(parte, flags, dir_fd=fd)
            atual = os.fstat(novo)
            if (anterior.st_dev, anterior.st_ino) != (atual.st_dev, atual.st_ino):
                os.close(novo)
                raise ValueError('caminho alterado durante abertura')
            os.close(fd)
            fd = novo
        return fd
    except BaseException:
        os.close(fd)
        raise


def ler_fd(fd):
    antes = os.fstat(fd)
    if not stat.S_ISREG(antes.st_mode) or antes.st_size > LIMITE:
        raise ValueError('insumo/arquivo não regular ou maior que 8 MiB')
    dados = bytearray()
    while True:
        bloco = os.read(fd, min(65536, LIMITE + 1 - len(dados)))
        if not bloco:
            break
        dados.extend(bloco)
        if len(dados) > LIMITE:
            raise ValueError('arquivo maior que 8 MiB')
    depois = os.fstat(fd)
    if (antes.st_dev, antes.st_ino, antes.st_size, antes.st_mtime_ns, antes.st_ctime_ns) != (
            depois.st_dev, depois.st_ino, depois.st_size, depois.st_mtime_ns, depois.st_ctime_ns):
        raise ValueError('arquivo alterado durante leitura')
    return bytes(dados), antes


def ler_caminho(caminho):
    fd = abrir_sem_links(caminho)
    try:
        return ler_fd(fd)
    finally:
        os.close(fd)


def criar(dirfd, nome, dados):
    fd = os.open(nome, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC, 0o600, dir_fd=dirfd)
    with os.fdopen(fd, 'wb') as arquivo:
        arquivo.write(dados)


def ler_resultado(dirfd, nome):
    anterior = os.stat(nome, dir_fd=dirfd, follow_symlinks=False)
    if not stat.S_ISREG(anterior.st_mode):
        raise ValueError('resultado não é arquivo regular: ' + nome)
    fd = os.open(nome, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK, dir_fd=dirfd)
    try:
        dados, st = ler_fd(fd)
        if ((anterior.st_dev, anterior.st_ino) != (st.st_dev, st.st_ino)
                or st.st_nlink != 1 or st.st_uid != os.geteuid()):
            raise ValueError('resultado com links ou proprietário inesperado: ' + nome)
        return dados
    finally:
        os.close(fd)


def resumo(dados):
    return {'bytes': len(dados), 'sha256': hashlib.sha256(dados).hexdigest()}


def limitar_arquivo():
    os.setsid()
    resource.setrlimit(resource.RLIMIT_FSIZE, (LIMITE, LIMITE))


def membros_ativos(pgid):
    # Linux /proc precisa apresentar todos os processos da sessão/namespace.
    ativos = []
    viu_proprio = False
    viu_lider = False
    for nome in os.listdir('/proc'):
        if not nome.isascii() or not nome.isdigit():
            continue
        try:
            with open('/proc/' + nome + '/stat', 'rb') as arquivo:
                texto = arquivo.read()
        except FileNotFoundError:
            continue
        fim = texto.rfind(b')')
        campos = texto[fim + 2:].split()
        if fim < 0 or len(campos) < 4:
            raise ValueError('não foi possível inspecionar grupo de processos')
        pid = int(nome)
        if pid == os.getpid():
            viu_proprio = True
        if pid == pgid:
            if int(campos[2]) != pgid or int(campos[3]) != pgid:
                raise ValueError('líder não pertence à sessão e grupo esperados')
            viu_lider = True
        if int(campos[2]) == pgid and pid != pgid and campos[0] not in (b'Z', b'X', b'x'):
            ativos.append(int(nome))
    if not viu_lider or not viu_proprio:
        raise ValueError('/proc não apresenta processo atual e líder')
    return ativos


def aguardar_processo(processo, timeout, resultado):
    # WNOWAIT mantém o líder unreaped, reservando o PID/PGID até acabar a inspeção.
    if not sys.platform.startswith('linux') or not hasattr(os, 'waitid') or not hasattr(os, 'WNOWAIT'):
        raise ValueError('requer Linux com /proc e waitid WNOWAIT')
    prazo = time.monotonic() + timeout
    encerrado_grupo = False
    pode_ter_ativos = True
    try:
        while not os.waitid(os.P_PID, processo.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT):
            if time.monotonic() >= prazo:
                resultado['timeout'] = True
                os.killpg(processo.pid, signal.SIGKILL)
                encerrado_grupo = True
                os.waitid(os.P_PID, processo.pid, os.WEXITED | os.WNOWAIT)
                break
            time.sleep(0.02)
        ativos = membros_ativos(processo.pid)
        if ativos:
            resultado['descendentes_ativos'] = True
            if not encerrado_grupo:
                os.killpg(processo.pid, signal.SIGKILL)
                encerrado_grupo = True
            prazo_fim = time.monotonic() + 2
            while membros_ativos(processo.pid):
                if time.monotonic() >= prazo_fim:
                    raise ValueError('não foi possível confirmar encerramento do grupo')
                time.sleep(0.02)
        pode_ter_ativos = False
    finally:
        if not encerrado_grupo and pode_ter_ativos:
            # Também fecha o grupo se a inspeção falhar; o líder ainda reserva o PGID.
            try:
                os.killpg(processo.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        resultado['rc_java'] = processo.wait()


def executar(args):
    if (os.name != 'posix' or not hasattr(os, 'O_NOFOLLOW') or not sys.platform.startswith('linux')
            or not hasattr(os, 'waitid') or not hasattr(os, 'WNOWAIT')):
        raise ValueError('requer Linux com O_NOFOLLOW, /proc e waitid WNOWAIT')
    raiz = abrir_sem_links(args.raiz, diretorio=True)
    try:
        st = os.fstat(raiz)
        if st.st_uid != os.geteuid() or stat.S_IMODE(st.st_mode) != 0o700:
            raise ValueError('raiz deve pertencer ao usuário efetivo e ter modo 0700')
        nome = 'homologacao-' + secrets.token_hex(16)
        os.mkdir(nome, 0o700, dir_fd=raiz)
        trabalho = os.open(nome, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=raiz)
        os.fchmod(trabalho, 0o700)
    finally:
        os.close(raiz)
    pasta = args.raiz.rstrip('/') + '/' + nome
    print('Evidências:', pasta, flush=True)
    resultado = {'versao': 1, 'resultado': 'INCONCLUSIVO', 'rc_java': None,
                 'timeout': False, 'descendentes_ativos': False, 'arquivos': {}, 'erro': None}
    rc = 1
    try:
        fixture, fi = ler_caminho(args.fixture)
        criar(trabalho, 'fixture.dat', fixture)
        resultado['arquivos']['fixture.dat'] = resumo(fixture)
        golden, go = ler_caminho(args.golden)
        criar(trabalho, 'golden.bin', golden)
        resultado['arquivos']['golden.bin'] = resumo(golden)
        jar, ja = ler_caminho(args.jar)
        resultado['arquivos']['jar'] = resumo(jar)
        if len({(s.st_dev, s.st_ino) for s in (fi, go, ja)}) != 3:
            raise ValueError('fixture, golden e JAR devem ser arquivos distintos')
        if not any(linha and not linha.startswith(b'#') and len(linha.split(b'|')) >= 10
                   for linha in fixture.splitlines()):
            raise ValueError('fixture sem registros aceitos; fallback mock não permitido')
        if not golden or len(golden) % 132:
            raise ValueError('golden deve ter registros de 132 bytes não vazios')
        java = shutil.which('java')
        if not java:
            raise ValueError('java não encontrado no PATH confiável')
        java = os.path.realpath(java)
        env = {'HOME': pasta, 'TMPDIR': pasta, 'LANG': 'C', 'LC_ALL': 'C', 'TZ': 'UTC'}
        comando = [java, '-jar', args.jar, '--fonte', 'mock', '--mock', pasta + '/fixture.dat',
                   '--saida', pasta + '/SAIDA', '--log', pasta + '/LOGFILE']
        erro_inspecao = None
        out = os.open('stdout', os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=trabalho)
        try:
            err = os.open('stderr', os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=trabalho)
            try:
                processo = subprocess.Popen(comando, cwd=pasta, env=env, stdin=subprocess.DEVNULL,
                                            stdout=out, stderr=err, preexec_fn=limitar_arquivo)
                try:
                    aguardar_processo(processo, args.timeout, resultado)
                except (OSError, ValueError) as exc:
                    erro_inspecao = str(exc)
            finally:
                os.close(err)
        finally:
            os.close(out)
        for arquivo in ('stdout', 'stderr', 'SAIDA', 'LOGFILE'):
            try:
                dados = ler_resultado(trabalho, arquivo)
                resultado['arquivos'][arquivo] = resumo(dados)
            except (OSError, ValueError) as exc:
                resultado['arquivos'][arquivo] = {'erro': str(exc)}
        if erro_inspecao is not None:
            raise ValueError('inspeção de processos falhou: ' + erro_inspecao)
        if resultado['timeout'] or resultado['rc_java'] != 0:
            raise ValueError('processo não terminou com RC 0')
        if resultado['descendentes_ativos']:
            raise ValueError('processo terminou com descendentes ativos no grupo')
        if any('erro' in resultado['arquivos'][nome] for nome in ('stdout', 'stderr')):
            raise ValueError('captura stdout/stderr ausente ou inválida')
        if any(resultado['arquivos'][nome]['bytes'] >= LIMITE for nome in ('stdout', 'stderr')):
            raise ValueError('captura stdout/stderr no limite pode estar truncada')
        if ler_caminho(args.jar)[0] != jar:
            raise ValueError('JAR mudou durante execução')
        if ler_resultado(trabalho, 'fixture.dat') != fixture or ler_resultado(trabalho, 'golden.bin') != golden:
            raise ValueError('snapshot de insumo alterado durante execução')
        saida = ler_resultado(trabalho, 'SAIDA')
        log = ler_resultado(trabalho, 'LOGFILE')
        if not saida or len(saida) % 132:
            raise ValueError('SAIDA ausente, vazia ou sem registros de 132 bytes')
        esperado_log = b'   PGMEXTRT INFO  INICIO DO PROCESSAMENTO DO EXTRATO DE CREDITO'
        if (len(log) != 132 or not log[:6].isdigit()
                or int(log[:2]) > 23 or int(log[2:4]) > 59 or int(log[4:6]) > 59
                or log[6:] != esperado_log.ljust(126, b' ')):
            raise ValueError('LOGFILE ausente ou inválido')
        if saida != golden:
            resultado['resultado'] = 'DIFERENTE'
            raise ValueError('SAIDA difere do golden por bytes')
        resultado['resultado'] = 'IGUAL'
        rc = 0
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        resultado['erro'] = str(exc)
    finally:
        try:
            criar(trabalho, 'resultado.json', (json.dumps(resultado, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
        finally:
            os.close(trabalho)
    return rc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--somente-sinteticos', action='store_true', required=True,
                        help='confirma que fixture e golden são exclusivamente sintéticos')
    parser.add_argument('--raiz', required=True, help='diretório privado existente, caminho absoluto')
    parser.add_argument('--fixture', required=True, help='mock sintético existente, caminho absoluto')
    parser.add_argument('--golden', required=True, help='esperado independente existente, caminho absoluto')
    parser.add_argument('--jar', required=True, help='quarkus-run.jar empacotado confiável, caminho absoluto')
    parser.add_argument('--timeout', type=int, default=60, choices=range(1, 601), metavar='1..600')
    args = parser.parse_args()
    os.umask(0o077)
    try:
        return executar(args)
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        print('Falha fechada:', exc, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
