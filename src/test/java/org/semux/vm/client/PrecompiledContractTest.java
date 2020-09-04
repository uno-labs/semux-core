/**
 * Copyright (c) 2017-2018 The Semux Developers
 *
 * Distributed under the MIT software license, see the accompanying file
 * LICENSE or https://opensource.org/licenses/mit-license.php
 */
package org.semux.vm.client;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.semux.core.Amount.ONE;
import static org.semux.core.Amount.ZERO;
import static org.semux.core.Unit.SEM;

import java.util.Random;

import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.semux.Network;
import org.semux.config.Config;
import org.semux.config.Constants;
import org.semux.config.DevnetConfig;
import org.semux.core.Amount;
import org.semux.core.BlockHeader;
import org.semux.core.Blockchain;
import org.semux.core.BlockchainImpl;
import org.semux.core.Transaction;
import org.semux.core.TransactionExecutor;
import org.semux.core.TransactionResult;
import org.semux.core.TransactionType;
import org.semux.core.state.AccountState;
import org.semux.core.state.DelegateState;
import org.semux.crypto.Hex;
import org.semux.crypto.Key;
import org.semux.rules.TemporaryDatabaseRule;
import org.semux.util.Bytes;
import org.semux.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PrecompiledContractTest {
    private Logger logger = LoggerFactory.getLogger(VmTransactionTest.class);

    @Rule
    public TemporaryDatabaseRule temporaryDBFactory = new TemporaryDatabaseRule();

    private Config config;
    private Blockchain chain;
    private AccountState as;
    private DelegateState ds;
    private Network network;

    @Before
    public void prepare() {
        config = new DevnetConfig(Constants.DEFAULT_DATA_DIR);
        chain = spy(new BlockchainImpl(config, temporaryDBFactory));
        doReturn(true).when(chain).isForkActivated(any());

        as = chain.getAccountState();
        ds = chain.getDelegateState();
        network = config.network();
    }

    // pragma solidity ^0.4.14;
    //
    // contract Vote {
    // function vote(address to, uint amount) {
    // require(0x0000000000000000000000000000000000000064.call(to, amount));
    // }
    //
    // function unvote(address to, uint amount) {
    // require(0x0000000000000000000000000000000000000065.call(to, amount));
    // }
    // }
    private byte[] codePostDeploy = Hex.decode(
            "60806040526004361061004c576000357c0100000000000000000000000000000000000000000000000000000000900463ffffffff16806302aa9be2146100515780635f74bbde1461009e575b600080fd5b34801561005d57600080fd5b5061009c600480360381019080803573ffffffffffffffffffffffffffffffffffffffff169060200190929190803590602001909291905050506100eb565b005b3480156100aa57600080fd5b506100e9600480360381019080803573ffffffffffffffffffffffffffffffffffffffff16906020019092919080359060200190929190505050610165565b005b606573ffffffffffffffffffffffffffffffffffffffff168282604051808373ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff168152602001828152602001925050506000604051808303816000865af1915050151561016157600080fd5b5050565b606473ffffffffffffffffffffffffffffffffffffffff168282604051808373ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff168152602001828152602001925050506000604051808303816000865af191505015156101db57600080fd5b50505600a165627a7a723058205e1325476a012c885717ccd0ad038a0d8d6c20f2e5afcb1b475d3eac5313c9500029");

    @Test
    public void testSuccess() {
        TransactionExecutor exec = new TransactionExecutor(config, new SemuxBlockStore(chain), true, true,
                true);
        Key key = new Key();
        byte[] delegate = Bytes.random(20);

        TransactionType type = TransactionType.CALL;
        byte[] from = key.toAddress();
        byte[] to = Bytes.random(20);
        Amount value = Amount.of(0);
        long nonce = as.getAccount(from).getNonce();
        long timestamp = TimeUtil.currentTimeMillis();
        byte[] data = Bytes.merge(Hex.decode("5f74bbde"),
                Hex.decode("000000000000000000000000"), delegate,
                Hex.decode("000000000000000000000000000000000000000000000000000000003B9ACA00")); // 1 nanoSEM
        long gas = 100000;
        Amount gasPrice = Amount.of(1);

        Transaction tx = new Transaction(network, type, to, value, ZERO, nonce, timestamp, data, gas, gasPrice);
        tx.sign(key);

        SemuxBlock block = new SemuxBlock(
                new BlockHeader(123, Bytes.random(20), Bytes.random(20), TimeUtil.currentTimeMillis(),
                        Bytes.random(20), Bytes.random(20), Bytes.random(20), Bytes.random(20)),
                config.spec().maxBlockGasLimit());
        as.adjustAvailable(from, Amount.of(1000, SEM));
        as.adjustAvailable(to, Amount.of(1000, SEM));
        as.setCode(to, codePostDeploy);
        ds.register(delegate, "abc".getBytes());

        TransactionResult result = exec.execute(tx, as, ds, block, 0);
        logger.info("Result: {}", result);

        assertTrue(result.getCode().isSuccess());
        long dataGasCost = 0;
        for (byte b : data) {
            dataGasCost += (b == 0 ? 4 : 68);
        }
        assertEquals(21_000 + 21_000 + dataGasCost + 1088, result.getGasUsed());
        assertEquals(Amount.of(1000, SEM).subtract(gasPrice.multiply(result.getGasUsed())),
                as.getAccount(from).getAvailable());
        assertEquals(Amount.of(1000, SEM).subtract(ONE), as.getAccount(to).getAvailable());
        assertEquals(ONE, as.getAccount(to).getLocked());
        assertEquals(ZERO, as.getAccount(delegate).getAvailable());
        assertEquals(ZERO, as.getAccount(delegate).getLocked());

        data = Bytes.merge(Hex.decode("02aa9be2"),
                Hex.decode("000000000000000000000000"), delegate,
                Hex.decode("000000000000000000000000000000000000000000000000000000003B9ACA00")); // 1 nanoSEM

        tx = new Transaction(network, type, to, value, ZERO, nonce + 1, timestamp, data, gas, gasPrice);
        tx.sign(key);

        result = exec.execute(tx, as, ds, block, 0);
        logger.info("Result: {}", result);

        assertTrue(result.getCode().isSuccess());
        assertEquals(Amount.of(1000, SEM), as.getAccount(to).getAvailable());
        assertEquals(ZERO, as.getAccount(to).getLocked());
        assertEquals(ZERO, as.getAccount(delegate).getAvailable());
        assertEquals(ZERO, as.getAccount(delegate).getLocked());
    }

    // call a smart contract, which invokes the vote pre-compiled
    // it would fail because the contract doesn't have balance.
    @Test
    public void testFailure1() {
        TransactionExecutor exec = new TransactionExecutor(config, new SemuxBlockStore(chain), true, true,
                true);
        Key key = new Key();
        byte[] delegate = Bytes.random(20);

        TransactionType type = TransactionType.CALL;
        byte[] from = key.toAddress();
        byte[] to = Bytes.random(20);
        Amount value = Amount.of(0);
        long nonce = as.getAccount(from).getNonce();
        long timestamp = TimeUtil.currentTimeMillis();
        byte[] data = Bytes.merge(Hex.decode("5f74bbde"),
                Hex.decode("000000000000000000000000"), delegate,
                Hex.decode("000000000000000000000000000000000000000000000000000000003B9ACA00")); // 1 nanoSEM
        long gas = 100000;
        Amount gasPrice = Amount.of(1);

        Transaction tx = new Transaction(network, type, to, value, ZERO, nonce, timestamp, data, gas, gasPrice);
        tx.sign(key);

        SemuxBlock block = new SemuxBlock(
                new BlockHeader(123, Bytes.random(20), Bytes.random(20), TimeUtil.currentTimeMillis(),
                        Bytes.random(20), Bytes.random(20), Bytes.random(20), Bytes.random(20)),
                config.spec().maxBlockGasLimit());
        as.adjustAvailable(from, Amount.of(1000, SEM));
        as.adjustAvailable(to, Amount.of(0, SEM));
        as.setCode(to, codePostDeploy);
        ds.register(delegate, "abc".getBytes());

        TransactionResult result = exec.execute(tx, as, ds, block, 0);
        assertFalse(result.getCode().isSuccess());
    }

    // call a the vote precompiled contract directly
    // it would fail because the sender doesn't have enough balance.
    @Test
    public void testFailure2() {
        TransactionExecutor exec = new TransactionExecutor(config, new SemuxBlockStore(chain), true, true,
                true);
        Key key = new Key();

        TransactionType type = TransactionType.CALL;
        byte[] from = key.toAddress();
        byte[] to = Hex.decode0x("0x0000000000000000000000000000000000000064");
        byte[] delegate = Bytes.random(20);
        Amount value = Amount.of(0);
        long nonce = as.getAccount(from).getNonce();
        long timestamp = TimeUtil.currentTimeMillis();
        byte[] data = Bytes.merge(Hex.decode("5f74bbde"),
                Hex.decode("000000000000000000000000"), delegate,
                Hex.decode("00000000000000000000000000000000000000000000006C6B935B8BBD400000")); // 2000 SEM
        long gas = 100000;
        Amount gasPrice = Amount.of(1);

        Transaction tx = new Transaction(network, type, to, value, ZERO, nonce, timestamp, data, gas, gasPrice);
        tx.sign(key);

        SemuxBlock block = new SemuxBlock(
                new BlockHeader(123, Bytes.random(20), Bytes.random(20), TimeUtil.currentTimeMillis(),
                        Bytes.random(20), Bytes.random(20), Bytes.random(20), Bytes.random(20)),
                config.spec().maxBlockGasLimit());
        as.adjustAvailable(from, Amount.of(1000, SEM));
        as.adjustAvailable(to, Amount.of(1000, SEM));
        ds.register(delegate, "abc".getBytes());

        TransactionResult result = exec.execute(tx, as, ds, block, 0);
        assertFalse(result.getCode().isSuccess());
    }

    @Test
    public void testEd25519Vfy() {
        TransactionExecutor exec = new TransactionExecutor(config, new SemuxBlockStore(chain), true, true,
                true);
        Key key = new Key();

        byte[] message = new byte[32];
        new Random().nextBytes(message);

        Key signingKey = new Key();
        Key.Signature sig = signingKey.sign(message);
        byte[] signature = sig.getS();
        byte[] publicKey = sig.getA();

        // our signatures are (S,A), eth spec is (A,S)
        TransactionType type = TransactionType.CALL;
        byte[] from = key.toAddress();
        byte[] to = Hex.decode0x("0x0000000000000000000000000000000000000009");
        byte[] delegate = Bytes.random(20);
        Amount value = Amount.of(0);

        long nonce = as.getAccount(from).getNonce();
        long timestamp = TimeUtil.currentTimeMillis();
        byte[] data = Bytes.merge(message, publicKey, signature);

        long gas = 100000;
        Amount gasPrice = Amount.of(1);

        Transaction tx = new Transaction(network, type, to, value, ZERO, nonce, timestamp, data, gas, gasPrice);
        tx.sign(key);

        SemuxBlock block = new SemuxBlock(
                new BlockHeader(123, Bytes.random(20), Bytes.random(20), TimeUtil.currentTimeMillis(),
                        Bytes.random(20), Bytes.random(20), Bytes.random(20), Bytes.random(20)),
                config.spec().maxBlockGasLimit());
        as.adjustAvailable(from, Amount.of(1000, SEM));
        as.adjustAvailable(to, Amount.of(1000, SEM));
        ds.register(delegate, "abc".getBytes());

        TransactionResult result = exec.execute(tx, as, ds, block, 0);
        assertTrue(result.getCode().isSuccess());
        assertArrayEquals(new byte[0], result.getReturnData());

        // check if message is tampered
        nonce = as.getAccount(from).getNonce();
        timestamp = TimeUtil.currentTimeMillis();
        message[0] = (byte) ((int) message[0] + 1 % 8);
        data = Bytes.merge(message, publicKey, signature);

        tx = new Transaction(network, type, to, value, ZERO, nonce, timestamp, data, gas, gasPrice);
        tx.sign(key);

        block = new SemuxBlock(
                new BlockHeader(123, Bytes.random(20), Bytes.random(20), TimeUtil.currentTimeMillis(),
                        Bytes.random(20), Bytes.random(20), Bytes.random(20), Bytes.random(20)),
                config.spec().maxBlockGasLimit());
        as.adjustAvailable(from, Amount.of(1000, SEM));
        as.adjustAvailable(to, Amount.of(1000, SEM));
        ds.register(delegate, "abc".getBytes());

        result = exec.execute(tx, as, ds, block, 0);

        // call is still a success, but data is not empty
        assertTrue(result.getCode().isSuccess());
        Assert.assertThat(new byte[0], IsNot.not(IsEqual.equalTo(result.getReturnData())));

        // check that invalid set of data is failure
        nonce = as.getAccount(from).getNonce();
        timestamp = TimeUtil.currentTimeMillis();
        message[0] = (byte) ((int) message[0] + 1 % 8);
        data = Bytes.merge(message, publicKey); // no signature

        tx = new Transaction(network, type, to, value, ZERO, nonce, timestamp, data, gas, gasPrice);
        tx.sign(key);

        block = new SemuxBlock(
                new BlockHeader(123, Bytes.random(20), Bytes.random(20), TimeUtil.currentTimeMillis(),
                        Bytes.random(20), Bytes.random(20), Bytes.random(20), Bytes.random(20)),
                config.spec().maxBlockGasLimit());
        as.adjustAvailable(from, Amount.of(1000, SEM));
        as.adjustAvailable(to, Amount.of(1000, SEM));
        ds.register(delegate, "abc".getBytes());

        result = exec.execute(tx, as, ds, block, 0);

        // call is still a success, but data is not empty
        assertFalse(result.getCode().isSuccess());
        assertArrayEquals(new byte[0], result.getReturnData());
    }

    @Test
    public void testEd25519Vfy_Prefork() {
        TransactionExecutor exec = new TransactionExecutor(config, new SemuxBlockStore(chain), true, true,
                false);
        Key key = new Key();

        byte[] message = new byte[32];
        new Random().nextBytes(message);

        Key signingKey = new Key();
        Key.Signature sig = signingKey.sign(message);
        byte[] signature = sig.getS();
        byte[] publicKey = sig.getA();

        // our signatures are (S,A), eth spec is (A,S)
        TransactionType type = TransactionType.CALL;
        byte[] from = key.toAddress();
        byte[] to = Hex.decode0x("0x0000000000000000000000000000000000000009");
        byte[] delegate = Bytes.random(20);
        Amount value = Amount.of(0);

        long nonce = as.getAccount(from).getNonce();
        long timestamp = TimeUtil.currentTimeMillis();
        byte[] data = Bytes.merge(message, publicKey, signature);

        long gas = 100000;
        Amount gasPrice = Amount.of(1);

        Transaction tx = new Transaction(network, type, to, value, ZERO, nonce, timestamp, data, gas, gasPrice);
        tx.sign(key);

        SemuxBlock block = new SemuxBlock(
                new BlockHeader(123, Bytes.random(20), Bytes.random(20), TimeUtil.currentTimeMillis(),
                        Bytes.random(20), Bytes.random(20), Bytes.random(20), Bytes.random(20)),
                config.spec().maxBlockGasLimit());
        as.adjustAvailable(from, Amount.of(1000, SEM));
        as.adjustAvailable(to, Amount.of(1000, SEM));
        ds.register(delegate, "abc".getBytes());

        TransactionResult result = exec.execute(tx, as, ds, block, 0);
        // calls to nonimplemented precompiled contracts just return success/empty
        assertTrue(result.getCode().isSuccess());
        assertArrayEquals(new byte[0], result.getReturnData());

        // check if message is tampered
        nonce = as.getAccount(from).getNonce();
        timestamp = TimeUtil.currentTimeMillis();
        message[0] = (byte) ((int) message[0] + 1 % 8);
        data = Bytes.merge(message, publicKey, signature);

        tx = new Transaction(network, type, to, value, ZERO, nonce, timestamp, data, gas, gasPrice);
        tx.sign(key);

        block = new SemuxBlock(
                new BlockHeader(123, Bytes.random(20), Bytes.random(20), TimeUtil.currentTimeMillis(),
                        Bytes.random(20), Bytes.random(20), Bytes.random(20), Bytes.random(20)),
                config.spec().maxBlockGasLimit());
        as.adjustAvailable(from, Amount.of(1000, SEM));
        as.adjustAvailable(to, Amount.of(1000, SEM));
        ds.register(delegate, "abc".getBytes());

        result = exec.execute(tx, as, ds, block, 0);
        // calls to nonimplemented precompiled contracts just return success/empty
        assertTrue(result.getCode().isSuccess());
        assertArrayEquals(new byte[0], result.getReturnData());

        // check that invalid set of data is not a failure (since it should default to
        // default calling of non-address
        nonce = as.getAccount(from).getNonce();
        timestamp = TimeUtil.currentTimeMillis();
        message[0] = (byte) ((int) message[0] + 1 % 8);
        data = Bytes.merge(message, publicKey); // no signature

        tx = new Transaction(network, type, to, value, ZERO, nonce, timestamp, data, gas, gasPrice);
        tx.sign(key);

        block = new SemuxBlock(
                new BlockHeader(123, Bytes.random(20), Bytes.random(20), TimeUtil.currentTimeMillis(),
                        Bytes.random(20), Bytes.random(20), Bytes.random(20), Bytes.random(20)),
                config.spec().maxBlockGasLimit());
        as.adjustAvailable(from, Amount.of(1000, SEM));
        as.adjustAvailable(to, Amount.of(1000, SEM));
        ds.register(delegate, "abc".getBytes());

        result = exec.execute(tx, as, ds, block, 0);

        // call is still a success, but data is not empty
        assertTrue(result.getCode().isSuccess());
        assertArrayEquals(new byte[0], result.getReturnData());

    }
}
