package com.polymarket.hft.polymarket.onchain;

public record ContractConfig(
        String exchange,
        String negRiskAdapter,
        String negRiskExchange,
        String collateral,
        String conditionalTokens,
        int collateralTokenDecimals,
        int conditionalTokenDecimals
) {
    public static ContractConfig forChainId(int chainId) {
        return switch (chainId) {
            case 137 -> new ContractConfig(
                    "0x4bFb41d5B3570DeFd03C39a9A4D8dE6Bd8B8982E",
                    "0xd91E80cF2E7be2e162c6513ceD06f1dD0dA35296",
                    "0xC5d563A36AE78145C45a50134d48A1215220f80a",
                    "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174",
                    "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045",
                    6,
                    6
            );
            case 80002 -> new ContractConfig(
                    "0xdFE02Eb6733538f8Ea35D585af8DE5958AD99E40",
                    "0xd91E80cF2E7be2e162c6513ceD06f1dD0dA35296",
                    "0xC5d563A36AE78145C45a50134d48A1215220f80a",
                    "0x9c4e1703476e875070ee25b56a58b008cfb8fa78",
                    "0x69308FB512518e39F9b16112fA8d994F4e2Bf8bB",
                    6,
                    6
            );
            default -> throw new IllegalArgumentException("Unsupported chainId: " + chainId);
        };
    }
}

