package br.com.air.extrato;

import java.math.BigDecimal;

record Operacao(String conta, String contrato, String modalidade, String descricao,
                BigDecimal original, int parcelas, int pagas, BigDecimal saldo,
                String vencimento, String status) {}
