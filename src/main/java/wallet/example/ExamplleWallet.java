package wallet.example;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.params.TestNet3Params;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;

import java.io.File;

public class ExamplleWallet {
	private static boolean needToSended = true;

	public static void main(String[] args) throws Exception {
		NetworkParameters params = TestNet3Params.get();
		Address forwardingAddress = Address.fromString(params, "tb1qerzrlxcfu24davlur5sqmgzzgsal6wusda40er");
		Coin amountToSend = Coin.ofSat(546);

		SimpleWalletKit swk;

		if (args.length == 0) {
			//for run from ide with test wallet
			swk = new SimpleWalletKit(params, new File("testnet_.wallet"));
		} else if (args[0].equals("-new")) {
			//create new wallet
			swk = new SimpleWalletKit(params);
		} else {
			//work with existing wallet
			swk = new SimpleWalletKit(params, new File(args[0]));
		}

		swk.startAsync();
		swk.awaitRunning();

		print(swk.getShortWalletInfo());

		swk.wallet().addCoinsReceivedEventListener((w, tx, prevBalance, newBalance) -> {
			Futures.addCallback(tx.getConfidence().getDepthFuture(1), new FutureCallback<TransactionConfidence>() {
				@Override
				public void onSuccess(TransactionConfidence result) {
					print("Confirmation received.");
					print(swk.getTxInfo(tx));
					
					if (needToSended) {
						swk.forwardCoins(forwardingAddress, amountToSend);
						needToSended = false;
					} else if (args.length > 1 && args[1].equals("-loop")) {
						swk.forwardCoins(forwardingAddress, amountToSend);
					}
				}

				@Override
				public void onFailure(Throwable t) {
					throw new RuntimeException(t);
				}
			}, MoreExecutors.directExecutor());
		});

		swk.wallet().addCoinsSentEventListener((w, tx, prevBalance, newBalance) -> {
			print("Waiting for confirm.");
			print(swk.getTxInfo(tx));

			Futures.addCallback(tx.getConfidence().getDepthFuture(1), new FutureCallback<TransactionConfidence>() {
				@Override
				public void onSuccess(TransactionConfidence result) {
					print("CONFIRMED: " + tx.getTxId());					
				}

				@Override
				public void onFailure(Throwable t) {
					throw new RuntimeException(t);
				}
			}, MoreExecutors.directExecutor());
		});

		// for run with existing wallet
		// if i dont want send coins too this wallet you can uncomment line	below
//		swk.forwardCoins(forwardingAddress, amountToSend);

		printBalanse(swk);

		try {
			Thread.sleep(Long.MAX_VALUE);
		} catch (InterruptedException ignored) {
		}
	}

	private static void printBalanse(SimpleWalletKit swk) {
		new Thread() {
			public void run() {
				try {
					while (true) {
						print(swk.getBalance());
 						Thread.sleep(30000);
					}
				} catch (InterruptedException e) {
					print(e.toString());
				}
			}
		}.start();
	}

	private static void print(String s) {
		System.out.println(s);
	}

}