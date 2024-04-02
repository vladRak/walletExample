package wallet.example;

import static com.google.common.base.Preconditions.checkState;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.List;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Utils;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.crypto.MnemonicException;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.script.Script.ScriptType;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.SPVBlockStore;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.Wallet.BalanceType;

import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

public class SimpleWalletKit extends AbstractIdleService {

	private final NetworkParameters params;
	private volatile Wallet wallet;
	private volatile SPVBlockStore blockStore;
	private volatile BlockChain chain;
	private volatile PeerGroup peerGroup;
	private volatile Context context;
	private final File walletFile;
	private boolean isWalletNew = true;
	private final long creationTime = System.currentTimeMillis();

	private final static String lineSep = System.getProperty("line.separator");

	public SimpleWalletKit(NetworkParameters params) {
		this.params = params;
		this.context = new Context(params);
		this.walletFile = new File(creationTime + ".wallet");
	}

	public SimpleWalletKit(NetworkParameters params, File walletFile) {
		this.params = params;
		this.context = new Context(params);
		this.walletFile = walletFile;
		this.isWalletNew = false;
	}

	@Override
	protected void startUp() throws Exception {

		if (isWalletNew) {
			wallet = createWallet();
		} else {
			wallet = Wallet.loadFromFile(walletFile);
		}

		blockStore = new SPVBlockStore(params, new File(walletFile.getName().split("\\.")[0] + ".spvchain"));

		chain = new BlockChain(params, wallet, blockStore);
		chain.addWallet(wallet);

		peerGroup = new PeerGroup(params, chain);
		peerGroup.addWallet(wallet);
		peerGroup.addPeerDiscovery(new DnsDiscovery(params));
		peerGroup.startAsync();
		peerGroup.waitForPeers(1);
	
		installShutdownHook();
		
		System.out.println("Syncing chain...");
		peerGroup.downloadBlockChain();
	}

	@Override
	protected void shutDown() throws Exception {
		try {
			Context.propagate(context);
			peerGroup.stop();
			blockStore.close();
			if (walletFile != null)
				wallet.saveToFile(walletFile);

			peerGroup = null;
			wallet = null;
			blockStore = null;
			chain = null;
		} catch (BlockStoreException e) {
			throw new IOException(e);
		}
	}

	public void forwardCoins(Address forwardingAddress, Coin amountToSend) {
		try {
			System.out.println("Send to: " + forwardingAddress + " " + amountToSend.toPlainString() + " BTC");
			Transaction transaction = new Transaction(params);
			transaction.addInput(wallet().getUnspents().get(0));
			transaction.addOutput(amountToSend, forwardingAddress);

			List<TransactionOutput> unspents = wallet().getUnspents();
			SendRequest sendRequest = SendRequest.forTx(transaction);
			sendRequest.setFeePerVkb(Transaction.REFERENCE_DEFAULT_MIN_TX_FEE.multiply(3));

			wallet().completeTx(sendRequest);
			wallet().commitTx(sendRequest.tx);

			wallet().saveToFile(walletFile);

			peerGroup.broadcastTransaction(sendRequest.tx);			

		} catch (InsufficientMoneyException e) {
			System.out.println("There are not enough coins. Waiting for receiving.");
			ListenableFuture<Coin> balanceFuture = wallet().getBalanceFuture(amountToSend, BalanceType.AVAILABLE);
			FutureCallback<Coin> callback = new FutureCallback<Coin>() {
				public void onSuccess(Coin balance) {
					forwardCoins(forwardingAddress, amountToSend);
				}

				public void onFailure(Throwable t) {
					System.out.println("something went wrong");
				}
			};
			Futures.addCallback(balanceFuture, callback, MoreExecutors.directExecutor());
		} catch (IOException e) {
			e.printStackTrace();
		} 
	}

	public String getBalance() {
		StringBuffer sb = new StringBuffer();
		return "Balance: " + wallet().getBalance(BalanceType.ESTIMATED).toPlainString() + " BTC; ";
	}

	private Wallet createWallet() throws MnemonicException.MnemonicLengthException {

		byte[] randomness = SecureRandom.getSeed(16);
		List<String> mnemonicCode = MnemonicCode.INSTANCE.toMnemonic(randomness);
		DeterministicSeed seed = new DeterministicSeed(mnemonicCode, null, "", creationTime);
		Wallet wallet = Wallet.fromSeed(params, seed, ScriptType.P2PKH);

		return wallet;

	}

	public NetworkParameters params() {
		return params;
	}

	public BlockChain chain() {
		checkState(state() == State.STARTING || state() == State.RUNNING, "Cannot call until startup is complete");
		return chain;
	}

	public BlockStore store() {
		checkState(state() == State.STARTING || state() == State.RUNNING, "Cannot call until startup is complete");
		return blockStore;
	}

	public Wallet wallet() {
		checkState(state() == State.STARTING || state() == State.RUNNING, "Cannot call until startup is complete");
		return wallet;
	}

	public PeerGroup peerGroup() {
		checkState(state() == State.STARTING || state() == State.RUNNING, "Cannot call until startup is complete");
		return peerGroup;
	}

	public String getTxInfo(Transaction tx) {
		StringBuffer sb = new StringBuffer();
		long fee = (tx.getInputSum().getValue() > 0 ? tx.getInputSum().getValue() - tx.getOutputSum().getValue() : 0);

		sb.append("___________________________________________" + lineSep);
		sb.append("### Tx Hex:" + tx.getTxId() + lineSep);
		sb.append("Date and Time: " + tx.getUpdateTime().toString() + lineSep);
		sb.append("Amount Sent to me: " + tx.getValueSentToMe(wallet()).toFriendlyString() + lineSep);
		sb.append("Amount Sent from me:   " + tx.getValueSentFromMe(wallet()).toFriendlyString() + lineSep);
		sb.append("Fee: " + Coin.valueOf(fee).toFriendlyString() + lineSep);
		sb.append("Transaction Depth: " + tx.getConfidence().getDepthInBlocks() + lineSep);
		sb.append("Transaction Blocks: " + tx.getConfidence().toString() + lineSep);

		return sb.toString();
	}

	public String getShortWalletInfo() {
		DeterministicSeed keyChainSeed = wallet().getKeyChainSeed();
		String seedWords = Utils.SPACE_JOINER.join(keyChainSeed.getMnemonicCode());
		long creationTime = keyChainSeed.getCreationTimeSeconds();
		Address address = wallet().currentReceiveAddress();

		StringBuffer sb = new StringBuffer();

		sb.append("Wallet info:" + lineSep);
		sb.append("Mnemonic: " + seedWords + lineSep);
		sb.append("Creation: " + creationTime + lineSep);
		sb.append("Address:  " + address + lineSep);

		return sb.toString();
	}

	private void installShutdownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				try {
					stopAsync();
					awaitTerminated();
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		});
	}
}
