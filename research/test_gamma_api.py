#!/usr/bin/env python3
"""Test Gamma API to understand the response structure."""
import requests
import json

# Test fetching a specific market by slug
slug = "btc-updown-15m-1766010600"
url = f"https://gamma-api.polymarket.com/events?slug={slug}"

print(f"Fetching: {url}")
response = requests.get(url)
print(f"Status: {response.status_code}")

if response.status_code == 200:
    data = response.json()
    print(f"Response type: {type(data)}")
    print(f"Response length: {len(data) if isinstance(data, list) else 'N/A'}")

    if isinstance(data, list) and len(data) > 0:
        event = data[0]
        print(f"\n=== EVENT KEYS ===")
        print(list(event.keys()))

        print(f"\n=== EVENT SLUG ===")
        print(event.get('slug'))

        print(f"\n=== EVENT clobTokenIds ===")
        print(event.get('clobTokenIds'))

        print(f"\n=== EVENT outcomes ===")
        print(event.get('outcomes'))

        print(f"\n=== MARKETS ARRAY ===")
        markets = event.get('markets', [])
        print(f"Number of markets: {len(markets)}")

        if markets:
            market = markets[0]
            print(f"\n=== FIRST MARKET KEYS ===")
            print(list(market.keys()))

            print(f"\n=== FIRST MARKET slug ===")
            print(market.get('slug'))

            print(f"\n=== FIRST MARKET clobTokenIds ===")
            print(market.get('clobTokenIds'))

            print(f"\n=== FIRST MARKET outcomes ===")
            print(market.get('outcomes'))

            print(f"\n=== FULL FIRST MARKET (pretty) ===")
            print(json.dumps(market, indent=2))
    else:
        print("No data returned")
        print(json.dumps(data, indent=2))
else:
    print(f"Error: {response.text}")

